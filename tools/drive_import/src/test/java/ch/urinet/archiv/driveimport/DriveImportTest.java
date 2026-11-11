package ch.urinet.archiv.driveimport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Lauf gegen einen Stub des Archivs: Wiederholung bei 503, Abbruch bei 401, Ablehnungen, Bilanz (Req 12.7). */
class DriveImportTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private final Deque<Antwort> antworten = new ArrayDeque<>();
    private final List<String> anfragen = new ArrayList<>();
    private final List<Duration> gewartet = new ArrayList<>();
    private final StringWriter aus = new StringWriter();
    private final StringWriter fehler = new StringWriter();
    private Path tokenDatei;

    record Antwort(int status, String typ, String inhalt, String retryAfter) {
    }

    @BeforeEach
    void starten() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/dokumente", this::beantworten);
        server.start();
        tokenDatei = tmp.resolve("token");
        Files.writeString(tokenDatei, TokenDateiTest.TOKEN);
        Files.setPosixFilePermissions(tokenDatei, PosixFilePermissions.fromString("rw-------"));
    }

    @AfterEach
    void stoppen() {
        server.stop(0);
    }

    private void beantworten(HttpExchange tausch) throws IOException {
        byte[] koerper = tausch.getRequestBody().readAllBytes();
        anfragen.add(tausch.getRequestHeaders().getFirst("Authorization") + "|"
                + tausch.getRequestHeaders().getFirst("Content-Length") + "|" + koerper.length + "|"
                + new String(koerper, StandardCharsets.UTF_8));
        Antwort antwort = antworten.isEmpty() ? neu() : antworten.poll();
        tausch.getResponseHeaders().add("Content-Type", antwort.typ());
        if (antwort.retryAfter() != null) {
            tausch.getResponseHeaders().add("Retry-After", antwort.retryAfter());
        }
        byte[] inhalt = antwort.inhalt().getBytes(StandardCharsets.UTF_8);
        tausch.sendResponseHeaders(antwort.status(), inhalt.length);
        tausch.getResponseBody().write(inhalt);
        tausch.close();
    }

    private static Antwort neu() {
        return new Antwort(202, "application/json", "[{\"datei\":\"x\",\"status\":\"NEU\",\"id\":\"1\"}]", null);
    }

    private int lauf(String... zusatz) {
        List<String> args = new ArrayList<>(List.of("--archiv", "http://127.0.0.1:" + server.getAddress().getPort(),
                "--token-datei", tokenDatei.toString()));
        args.addAll(List.of(zusatz));
        args.add(tmp.resolve("UriNet").toString());
        return DriveImport.ausfuehren(new PrintWriter(aus), new PrintWriter(fehler), Map.of(), gewartet::add,
                args.toArray(String[]::new));
    }

    private void datei(String pfad, String inhalt) throws IOException {
        Path ziel = tmp.resolve("UriNet").resolve(pfad);
        Files.createDirectories(ziel.getParent());
        Files.writeString(ziel, inhalt);
    }

    @Test
    void ordnetEinZaehltUndSendetMitHinweisUndContentLength() throws IOException {
        datei("Kunden/FC Muster/Rechnungen/a.pdf", "%PDF-a");
        datei("Kunden/FC Muster/Rechnungen/b.pdf", "%PDF-b");
        datei("Kunden/FC Muster/Offerten/c.pdf", "%PDF-c");
        datei("Ablage/notiz.gdoc", "{}");
        datei("Ablage/brief.docx", "x");
        datei("Ablage/post.eml", "x");
        datei("Ablage/paket.zip", "x");
        datei("Ablage/.DS_Store", "x");
        antworten.add(new Antwort(415, "application/problem+json", "{\"status\":415,\"fehlercode\":"
                + "\"DATEITYP_UNBEKANNT\",\"detail\":\"Dateityp nicht unterstützt\",\"ergebnisse\":[{\"datei\":"
                + "\"c.pdf\",\"status\":\"ABGELEHNT\",\"fehler\":{\"status\":415,\"fehlercode\":"
                + "\"DATEITYP_UNBEKANNT\",\"detail\":\"Dateityp nicht unterstützt\"}}]}", null));

        antworten.add(new Antwort(202, "application/json",
                "[{\"datei\":\"a.pdf\",\"status\":\"NEU\",\"id\":\"1\"}]", null));
        antworten.add(new Antwort(202, "application/json",
                "[{\"datei\":\"b.pdf\",\"status\":\"DUBLETTE\",\"meldung\":\"bereits vorhanden\"}]", null));
        int code = lauf();

        assertThat(code).isEqualTo(DriveImport.ABGELEHNT);
        assertThat(anfragen).hasSize(3);
        for (String anfrage : anfragen) {
            String[] teile = anfrage.split("\\|", 4);
            assertThat(teile[0]).isEqualTo("Bearer " + TokenDateiTest.TOKEN);
            assertThat(teile[1]).isEqualTo(teile[2]);
        }
        assertThat(anfragen.get(0)).contains("name=\"hinweis\"",
                "Drive-Import; Ordner: UriNet/Kunden/FC Muster/Offerten; Partner: FC Muster; Art: OFFERTE",
                "filename=\"c.pdf\"", "%PDF-c");
        String bericht = aus.toString();
        assertThat(bericht).contains("Bitte als PDF exportieren (1):\n  UriNet/Ablage/notiz.gdoc",
                "UriNet/Ablage/brief.docx – " + Einordnung.GRUND_OFFICE,
                "UriNet/Ablage/post.eml – " + Einordnung.GRUND_EMAIL,
                "UriNet/Ablage/paket.zip – " + Einordnung.GRUND_ARCHIV,
                "UriNet/Ablage/.DS_Store – " + Einordnung.GRUND_SYSTEM,
                "UriNet/Kunden/FC Muster/Offerten/c.pdf – Dateityp nicht unterstützt (415 DATEITYP_UNBEKANNT)",
                "Kontrolle: 8 = 1 + 1 + 5 + 1 (geht auf)");
        assertThat(bericht + fehler).doesNotContain(TokenDateiTest.TOKEN.substring(16));
    }

    @Test
    void wiederholt503NachRetryAfterUndBrichtBei401Ab() throws IOException {
        datei("a.pdf", "%PDF-a");
        datei("b.pdf", "%PDF-b");
        datei("c.pdf", "%PDF-c");
        antworten.add(new Antwort(503, "application/problem+json",
                "{\"status\":503,\"fehlercode\":\"SPEICHER_NICHT_ERREICHBAR\"}", "30"));
        antworten.add(neu());
        antworten.add(new Antwort(401, "application/problem+json",
                "{\"status\":401,\"fehlercode\":\"NICHT_ANGEMELDET\"}", null));

        int code = lauf();

        assertThat(code).isEqualTo(DriveImport.ABGEBROCHEN);
        assertThat(gewartet).containsExactly(Duration.ofSeconds(30));
        assertThat(anfragen).hasSize(3);
        assertThat(aus.toString()).contains("UriNet/c.pdf – Lauf abgebrochen: Archiv lehnt das Token ab (401",
                "Kontrolle: 3 = 1 + 0 + 1 + 1 (geht auf)");
    }

    @Test
    void gibtNachZuVielen503AufUndZaehltAlsAbgelehnt() throws IOException {
        datei("a.pdf", "%PDF-a");
        for (int i = 0; i < ArchivClient.MAX_VERSUCHE; i++) {
            antworten.add(new Antwort(503, "application/problem+json",
                    "{\"status\":503,\"fehlercode\":\"UEBERLASTET\",\"detail\":\"Archiv ausgelastet\"}", "1"));
        }

        assertThat(lauf()).isEqualTo(DriveImport.ABGELEHNT);
        assertThat(gewartet).hasSize(ArchivClient.MAX_VERSUCHE - 1);
        assertThat(aus.toString()).contains("UriNet/a.pdf – Archiv ausgelastet (503 UEBERLASTET), bitte später");
    }

    @Test
    void trockenlaufLiestKeinTokenUndSendetNichts() throws IOException {
        datei("Buchhaltung 2026/a.pdf", "%PDF-a");
        datei("x.gsheet", "{}");
        tokenDatei = tmp.resolve("fehlt");

        assertThat(lauf("--trockenlauf")).isEqualTo(DriveImport.OK);
        assertThat(anfragen).isEmpty();
        assertThat(aus.toString()).contains("würde übertragen: UriNet/Buchhaltung 2026/a.pdf  [Drive-Import; "
                + "Ordner: UriNet/Buchhaltung 2026; Bereich: Buchhaltung; Jahr: 2026]",
                "Kontrolle: 2 = 1 + 1 (geht auf)");
    }

    @Test
    void verweigertHttpAusserhalbVonLocalhostUndLockereTokenDatei() throws IOException {
        datei("a.pdf", "%PDF-a");
        assertThat(DriveImport.ausfuehren(new PrintWriter(aus), new PrintWriter(fehler), Map.of(),
                "--archiv", "http://archiv.example", tmp.resolve("UriNet").toString()))
                .isEqualTo(DriveImport.UNGUELTIG);
        Files.setPosixFilePermissions(tokenDatei, PosixFilePermissions.fromString("rw-r--r--"));
        assertThat(lauf()).isEqualTo(DriveImport.UNGUELTIG);
        assertThat(anfragen).isEmpty();
        assertThat(fehler.toString()).contains("chmod 600");
    }
}
