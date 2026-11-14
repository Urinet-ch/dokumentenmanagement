package ch.urinet.archiv.abnahme;

import ch.urinet.archiv.abnahme.ChatPrueflauf.Lauf;
import ch.urinet.archiv.abnahme.Pruefliste.ErwarteteQuelle;
import ch.urinet.archiv.abnahme.Pruefliste.Frage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Logik von {@code ChatPrueflisteIT} gegen einen lokalen Fake-Server (Spec 02, Aufgabe 9.1): SSE lesen, Vorschlag
 * richtig/falsch und Quelle ja/nein, Berichte (voll nur unter dem Golden-Verzeichnis, Repo-Teil ohne Inhalte). Alle
 * Daten sind erfunden; es gibt keinen Zugriff auf Produktion oder Golden-Daten.
 */
class ChatPrueflaufTest {

    private static final String TOKEN = "geheimes-testtoken-0815";
    private static final UUID DOKUMENT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private final List<String> gestellteFragen = new CopyOnWriteArrayList<>();
    private final List<String> authorisierungen = new CopyOnWriteArrayList<>();
    private volatile Consumer<String> antworter;
    private URI basis;

    @BeforeEach
    void serverStarten() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/chat", this::behandeln);
        server.start();
        basis = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void serverStoppen() {
        server.stop(0);
    }

    private void behandeln(HttpExchange x) throws IOException {
        try (x) {
            authorisierungen.add(x.getRequestHeaders().getFirst("Authorization"));
            String frage = JSON.readTree(x.getRequestBody()).path("frage").asString();
            gestellteFragen.add(frage);
            if (!"POST".equals(x.getRequestMethod()) || !("Bearer " + TOKEN).equals(x.getRequestHeaders().getFirst("Authorization"))) {
                x.sendResponseHeaders(401, -1);
                return;
            }
            antworter.accept(frage);
            Antwortplan plan = plaene.get(frage);
            if (plan.status != 200) {
                x.sendResponseHeaders(plan.status, -1);
                return;
            }
            x.getResponseHeaders().add("Content-Type", "text/event-stream");
            x.sendResponseHeaders(200, 0);
            try {
                Thread.sleep(plan.verzoegerungMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            x.getResponseBody().write(plan.strom.getBytes(StandardCharsets.UTF_8));
            x.getResponseBody().flush();
        }
    }

    private record Antwortplan(int status, long verzoegerungMs, String strom) {
    }

    private final Map<String, Antwortplan> plaene = new java.util.HashMap<>();

    private static String ereignis(String name, String json) {
        return "event: " + name + "\ndata: " + json + "\n\n";
    }

    private static String text(String delta) {
        return ereignis("text", "{\"delta\":\"" + delta + "\"}");
    }

    private static final String FERTIG = ereignis("fertig", "{\"gespraechId\":\"" + UUID.randomUUID() + "\",\"tokens\":{\"ein\":10,\"aus\":5}}");
    private static final String QUELLEN = ereignis("quellen", "[{\"id\":\"" + DOKUMENT + "\",\"titel\":\"Testtitel\","
            + "\"link\":\"/dokument/" + DOKUMENT + "\"},{\"werkzeug\":\"kennzahl\",\"datenstand\":\"2026-11-14T10:00:00Z\",\"belege\":3}]");

    private static Frage frage(int nr, String text, List<String> kernwerte, List<ErwarteteQuelle> quellen) {
        return new Frage(nr, text, nr <= 7, kernwerte, quellen, nr == 2 ? "Erfundener Hinweis" : "");
    }

    private ChatPrueflauf lauf() {
        antworter = f -> {
        };
        return new ChatPrueflauf(basis, TOKEN, Duration.ZERO, Duration.ofSeconds(10));
    }

    @Test
    void richtigMitQuelleAusDemStrom() throws Exception {
        plaene.put("Wie hoch?", new Antwortplan(200, 0, ereignis("werkzeug", "{\"name\":\"kennzahl\"}")
                + text("Die Einnahmen sind CHF 1'2") + text("50.50 bei Testfirma Alpha.") + QUELLEN + FERTIG));
        Frage f = frage(1, "Wie hoch?", List.of("chf 1250.50", "TESTFIRMA alpha"),
                List.of(new ErwarteteQuelle(DOKUMENT, null), new ErwarteteQuelle(null, "kennzahl")));

        Lauf lauf = lauf().laufen(List.of(f));

        assertThat(lauf.abgebrochen()).isFalse();
        var e = lauf.ergebnisse().get(0);
        assertThat(e.richtig()).isTrue();
        assertThat(e.quelle()).isTrue();
        assertThat(e.antwort().text()).isEqualTo("Die Einnahmen sind CHF 1'250.50 bei Testfirma Alpha.");
        assertThat(e.antwort().quellen()).hasSize(2);
        assertThat(e.antwort().ersterTextMs()).isNotNegative();
        assertThat(authorisierungen).containsExactly("Bearer " + TOKEN);
    }

    @Test
    void fehlendeKernwerteUndQuellenGebenFalschUndNein() throws Exception {
        plaene.put("A?", new Antwortplan(200, 0, text("Nur Testfirma Alpha.") + QUELLEN + FERTIG));
        plaene.put("B?", new Antwortplan(200, 0, text("Antwort ohne Quelle.")
                + ereignis("quellen", "[]") + FERTIG));
        Frage a = frage(1, "A?", List.of("Testfirma Alpha", "CHF 9.00"), List.of(new ErwarteteQuelle(DOKUMENT, null)));
        Frage b = frage(2, "B?", List.of("Antwort"), List.of(new ErwarteteQuelle(null, "kennzahl"),
                new ErwarteteQuelle(UUID.randomUUID(), null)));

        Lauf lauf = lauf().laufen(List.of(a, b));

        var ea = lauf.ergebnisse().get(0);
        assertThat(ea.richtig()).isFalse();
        assertThat(ea.fehlendeKernwerte()).containsExactly("CHF 9.00");
        assertThat(ea.quelle()).isTrue();
        var eb = lauf.ergebnisse().get(1);
        assertThat(eb.richtig()).isTrue();
        assertThat(eb.quelle()).isFalse();
        assertThat(eb.fehlendeQuellen()).hasSize(2);
    }

    @Test
    void fehlerereignisHttpFehlerUndOhneFertigGeltenAlsFalsch() throws Exception {
        plaene.put("F?", new Antwortplan(200, 0, text("Testfirma") + ereignis("fehler",
                "{\"meldung\":\"Der Chat ist im Moment nicht verfügbar.\",\"gespraechId\":\"x\"}")));
        plaene.put("H?", new Antwortplan(429, 0, ""));
        plaene.put("O?", new Antwortplan(200, 0, text("Testfirma")));
        List<Frage> fragen = List.of(frage(1, "F?", List.of("Testfirma"), List.of()),
                frage(2, "H?", List.of("Testfirma"), List.of()), frage(3, "O?", List.of("Testfirma"), List.of()));

        Lauf lauf = lauf().laufen(fragen);

        assertThat(lauf.ergebnisse()).extracting(ChatPrueflauf.Ergebnis::richtig).containsExactly(false, false, false);
        assertThat(lauf.ergebnisse()).extracting(ChatPrueflauf.Ergebnis::quelle).containsExactly(false, false, false);
        assertThat(lauf.ergebnisse().get(1).antwort().status()).isEqualTo(429);
        assertThat(lauf.abgebrochen()).isFalse();
    }

    @Test
    void abgelaufenesTokenBrichtDenLaufAbUndNenntNurDenStatus(@TempDir Path tmp) throws Exception {
        plaene.put("1?", new Antwortplan(200, 0, text("Testfirma") + FERTIG));
        plaene.put("2?", new Antwortplan(200, 0, text("Testfirma") + FERTIG));
        antworter = f -> {
        };
        ChatPrueflauf falsch = new ChatPrueflauf(basis, "anderes-token", Duration.ZERO, Duration.ofSeconds(10));

        Lauf lauf = falsch.laufen(List.of(frage(1, "1?", List.of("Testfirma"), List.of()),
                frage(2, "2?", List.of("Testfirma"), List.of())));

        assertThat(lauf.abgebrochen()).isTrue();
        assertThat(lauf.abbruchStatus()).isEqualTo(401);
        assertThat(lauf.ergebnisse()).isEmpty();
        assertThat(gestellteFragen).containsExactly("1?");
        assertThat(ChatPrueflauf.repoTeil(lauf)).contains("abgebrochen").doesNotContain("anderes-token");
    }

    @Test
    void ersterTextWirdVorDemEndeGemessenUndPauseGilt() throws Exception {
        plaene.put("L?", new Antwortplan(200, 150, text("Testfirma") + FERTIG));
        plaene.put("M?", new Antwortplan(200, 0, text("Testfirma") + FERTIG));
        antworter = f -> {
        };
        ChatPrueflauf mitPause = new ChatPrueflauf(basis, TOKEN, Duration.ofMillis(300), Duration.ofSeconds(10));
        long start = System.nanoTime();

        Lauf lauf = mitPause.laufen(List.of(frage(1, "L?", List.of("Testfirma"), List.of()),
                frage(2, "M?", List.of("Testfirma"), List.of())));

        assertThat(lauf.ergebnisse().get(0).antwort().ersterTextMs()).isGreaterThanOrEqualTo(100);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(450));
        assertThat(ChatPrueflauf.p95(lauf.ergebnisse())).isGreaterThanOrEqualTo(100);
    }

    @Test
    void p95NimmtDenNaechstenRang() {
        List<ChatPrueflauf.Ergebnis> e = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            e.add(new ChatPrueflauf.Ergebnis(frage(i, "x", List.of("x"), List.of()),
                    new ChatPrueflauf.Antwort(200, "x", List.of(), true, null, i * 100L), true, true, List.of(), List.of()));
        }
        assertThat(ChatPrueflauf.p95(e)).isEqualTo(1900);
        assertThat(ChatPrueflauf.p95(List.of())).isEqualTo(-1);
    }

    @Test
    void berichtVollNurMitInhaltenRepoTeilOhneInhaltUndOhneToken(@TempDir Path golden) throws Exception {
        plaene.put("Wie hoch ist der Erfundene Betrag?", new Antwortplan(200, 0,
                text("Erfundener Betrag CHF 77.00.") + QUELLEN + FERTIG));
        plaene.put("Zweite?", new Antwortplan(200, 0, text("Falsch.") + ereignis("quellen", "[]") + FERTIG));
        Frage a = frage(1, "Wie hoch ist der Erfundene Betrag?", List.of("CHF 77.00"), List.of(new ErwarteteQuelle(DOKUMENT, null)));
        Frage b = frage(2, "Zweite?", List.of("Anderer Kernwert"), List.of());
        Lauf lauf = lauf().laufen(List.of(a, b));

        Map<String, Path> dateien = ChatPrueflauf.schreiben(lauf, golden,
                Clock.fixed(Instant.parse("2026-11-20T08:15:30Z"), ZoneOffset.UTC));

        assertThat(dateien.get("bericht").getFileName().toString()).isEqualTo("chat-pruefliste-bericht-20261120-081530.md");
        String voll = Files.readString(dateien.get("bericht"));
        assertThat(voll).contains("Wie hoch ist der Erfundene Betrag?", "Erfundener Betrag CHF 77.00.", DOKUMENT.toString(),
                "Erfundener Hinweis", "Bestätigung Fabian", "Kernwerte nicht gefunden: [Anderer Kernwert]")
                .doesNotContain(TOKEN);
        String repo = Files.readString(dateien.get("repoTeil"));
        assertThat(repo).contains("| 1 | richtig | ja |", "| 2 | falsch | ja |", "Vorschlag richtig mit Quelle: 1 von 2",
                "Beispielfragen 1 von 7", "p95")
                .doesNotContain("Erfundener", "CHF 77", DOKUMENT.toString(), "Zweite", "Anderer Kernwert", TOKEN, "Testtitel");
        try {
            assertThat(Files.getPosixFilePermissions(dateien.get("bericht"))).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        } catch (UnsupportedOperationException e) {
            // kein POSIX-Dateisystem
        }
        assertThatThrownBy(() -> ChatPrueflauf.schreiben(lauf, golden.resolve("fehlt"), Clock.systemUTC()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void tokenFehltOderLeer() {
        assertThatThrownBy(() -> new ChatPrueflauf(basis, " ", Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(TOKEN);
    }

    @Test
    void nichtErreichbarerServerNenntNurDenKlassennamen() throws Exception {
        server.stop(0);
        Lauf lauf = lauf().laufen(List.of(frage(1, "X?", List.of("x"), List.of())));

        var a = lauf.ergebnisse().get(0).antwort();
        assertThat(lauf.ergebnisse().get(0).richtig()).isFalse();
        assertThat(a.fehlermeldung()).doesNotContain("127.0.0.1", TOKEN);
        assertThat(Set.of(a.status())).containsExactly(0);
    }
}
