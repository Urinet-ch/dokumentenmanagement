package ch.urinet.archiv.dokument;

import ch.urinet.archiv.ablage.S3Stoerung;
import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.dokument.application.DokumentErfassen;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.builder.MultiPartSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Aufgabe 2.4 (Req 2.7, 3.1–3.8, 4.7, 6.1) gegen {@code POST /api/v1/dokumente} mit synthetischen Dateien
 * ({@link Testdateien}). Nach jedem Szenario löscht der Schritt die angelegten Dokumente, ihre Dateien unter
 * {@code eingang/}, die Aufträge {@code VERARBEITEN} und die Tokens; eine simulierte Störung des Speichers endet.
 */
public class UploadSchritte {

    private static final String PFAD = "/api/v1/dokumente";
    private static final java.util.regex.Pattern ZEICHEN = java.util.regex.Pattern.compile("\\{U\\+([0-9A-F]{4,6})}");
    private static final java.util.regex.Pattern WIEDERHOLT = java.util.regex.Pattern.compile(
            "\\{(\\d+)×(Leerzeichen|.)}");
    /** Teile eines Uploads (quarkus.http.body.uploads-directory) im Arbeitsverzeichnis archiv.arbeit.verzeichnis. */
    private static final Path TEMP = Path.of(ConfigProvider.getConfig().getValue("quarkus.http.body.uploads-directory",
            String.class));
    private static final Path ARBEIT = Path.of(ConfigProvider.getConfig().getValue("archiv.arbeit.verzeichnis",
            String.class));
    /**
     * RFC 6532 sendet Namen als UTF-8 und den Content-Type der Teile ohne Parameter wie ein Browser; BROWSER_COMPATIBLE
     * liess bei einem leeren Namen auch den Content-Type weg, der Teil kam dann als Textfeld an.
     */
    private static final RestAssuredConfig UTF8 = RestAssuredConfig.config().httpClient(
            HttpClientConfig.httpClientConfig().httpMultipartMode(HttpMultipartMode.RFC6532));

    /** Eine gesendete Datei: Name im Szenario und Inhalt. */
    private record Datei(String name, byte[] inhalt, byte[] arbeitsfassung) {
    }

    /**
     * Zählstand eines Mandanten vor dem Upload für «nichts gespeichert»; dazu je Prüfsumme der gesendeten Bytes die
     * vorhandenen Zeilen in {@code dokument_datei}. Die Zeilen statt eines Zeitvergleichs: {@code empfangen} setzt die
     * JVM, die Datenbank im Container hat eine eigene Uhr, die nachgehen kann.
     */
    private record Stand(long dokumente, long dateien, long auftraege, List<String> eingang,
                         Map<String, List<String>> zeilenJeSha) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    ApiTokenVerwaltung tokens;

    private final Map<String, byte[]> inhalte = new HashMap<>();
    private final Map<String, Set<UUID>> angelegt = new HashMap<>();
    private final Map<String, UUID> dokumentVon = new HashMap<>();
    private final List<String> tokenIds = new ArrayList<>();
    private List<Datei> gesendet = List.of();
    private String mandant;
    private Stand vorher;
    private Set<Path> tempVorher = Set.of();
    private Set<Path> arbeitVorher = Set.of();
    private List<Response> parallel = List.of();
    /** Während paralleler Uploads beobachtete Teile im Upload-Verzeichnis. */
    private final Set<Path> beobachtet = ConcurrentHashMap.newKeySet();
    private Response antwort;
    private HttpResponse<String> rohAntwort;
    private LogMitschnitt mitschnitt;
    private List<Response> geoeffnet = List.of();

    @After
    public void aufraeumen() throws SQLException {
        S3Stoerung.aus();
        if (mitschnitt != null) {
            mitschnitt.close();
            mitschnitt = null;
        }
        for (Map.Entry<String, Set<UUID>> eintrag : angelegt.entrySet()) {
            List<UUID> ids = List.copyOf(eintrag.getValue());
            ausfuehren("delete from public.auftrag where typ in ('VERARBEITEN', 'KI_VORSCHLAG') "
                    + "and nutzlast->>'dokumentId' = any (string_to_array(?, ','))",
                    String.join(",", ids.stream().map(UUID::toString).toList()));
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), ids);
            Set<String> ordner = new HashSet<>(ids.stream().map(id -> "/" + id + "/").toList());
            TestAblage.loeschen(eintrag.getKey(), "eingang/", schluessel -> ordner.stream().anyMatch(schluessel::contains));
            TestAblage.loeschen(eintrag.getKey(), "vorschau/", schluessel -> ordner.stream()
                    .anyMatch(o -> schluessel.startsWith("vorschau" + o)));
        }
        angelegt.clear();
        for (String id : tokenIds) {
            ausfuehren("delete from public.api_client where id = ?", id);
        }
    }

    @Angenommen("das Log des Uploads wird mitgeschnitten")
    public void logMitschneiden() {
        mitschnitt = LogMitschnitt.starten();
    }

    @Wenn("«{word}» diese Dateien hochlädt:")
    public void hochladen(String benutzer, DataTable tabelle) {
        senden(benutzer, dateien(tabelle), false, null);
    }

    @Wenn("«{word}» diese Dateien als ein Dokument mit dem Hinweis {string} hochlädt:")
    public void zusammenfassen(String benutzer, String hinweis, DataTable tabelle) {
        senden(benutzer, dateien(tabelle), true, hinweis);
    }

    @Wenn("«{word}» diese Dateien als ein Dokument mit einem Hinweis aus {int} × «{}» hochlädt:")
    public void langerHinweis(String benutzer, int anzahl, String zeichen, DataTable tabelle) {
        senden(benutzer, dateien(tabelle), true, zeichen.repeat(anzahl));
    }

    @Dann("nennen alle Ergebnisse dasselbe Dokument mit einem Hinweis aus {int} × «{}»")
    public void langerHinweisGespeichert(int anzahl, String zeichen) throws SQLException {
        dasselbeDokument(zeichen.repeat(anzahl));
    }

    @Wenn("«{word}» diese Seiten mit Arbeitsfassung als ein Dokument hochlädt:")
    public void seitenHochladen(String benutzer, DataTable tabelle) {
        senden(benutzer, dateien(tabelle), true, null);
    }

    @Angenommen("«{word}» hat die Datei «{}» \\({word}) hochgeladen")
    public void hochgeladen(String benutzer, String name, String typ) {
        senden(benutzer, List.of(new Datei(name, inhalt(name, typ), null)), false, null);
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(202);
        dokumentVon.put(name, UUID.fromString(antwort.path("[0].id")));
    }

    @Wenn("«{word}» die Datei «{}» erneut hochlädt")
    public void erneut(String benutzer, String name) {
        senden(benutzer, List.of(new Datei(name, inhalte.get(name), null)), false, null);
    }

    @Wenn("ein Import-Token von «{word}» die Datei «{}» erneut hochlädt")
    public void erneutMitToken(String mandantCode, String name) {
        ApiTokenVerwaltung.Erstellt erstellt = tokens.erstellen(mandantCode, "Probe Upload", "import");
        String token = erstellt.token().text();
        tokenIds.add(ApiToken.lesen(token).orElseThrow().id());
        List<Datei> dateien = List.of(new Datei(name, inhalte.get(name), null));
        stand(mandantCode, dateien);
        gesendet = dateien;
        antwort = multipart(given().header("Authorization", "Bearer " + token), dateien, false, null).when().post(PFAD);
        merken(mandantCode);
    }

    /**
     * Zeilen mit Typ {@code ohne-namen:<MIME>} gehen als Teil {@code dateien} ohne Dateinamen mit Text. Eine Spalte
     * {@code arbeitsfassung} hängt wie die Handy-App je Datei einen Teil {@code arbeitsfassungen} an und fasst zusammen.
     */
    @Wenn("«{word}» diese Teile «dateien» hochlädt:")
    public void teileHochladen(String benutzer, DataTable tabelle) {
        String mandantCode = benutzer.substring(benutzer.indexOf('-') + 1);
        List<Datei> dateien = new ArrayList<>();
        RequestSpecification spec = given().auth().oauth2(Testanmeldung.token(benutzer)).config(UTF8)
                .contentType("multipart/form-data; charset=UTF-8");
        for (Map<String, String> zeile : tabelle.asMaps()) {
            String typ = zeile.get("typ");
            if (typ.startsWith("ohne-namen:")) {
                spec = spec.multiPart(new MultiPartSpecBuilder("synthetischer Text").controlName("dateien")
                        .mimeType(typ.substring("ohne-namen:".length())).charset(StandardCharsets.UTF_8)
                        .emptyFileName().build());
            } else {
                String arbeit = zeile.get("arbeitsfassung");
                Datei datei = new Datei(zeile.get("datei"), inhalt(zeile.get("datei"), typ),
                        arbeit == null || arbeit.isBlank() ? null : Testdateien.erzeugen(arbeit));
                dateien.add(datei);
                spec = spec.multiPart(new MultiPartSpecBuilder(datei.inhalt()).controlName("dateien")
                        .fileName(datei.name()).mimeType("application/octet-stream").build());
            }
        }
        boolean mitArbeitsfassung = false;
        for (Datei datei : dateien) {
            if (datei.arbeitsfassung() != null) {
                mitArbeitsfassung = true;
                spec = spec.multiPart(new MultiPartSpecBuilder(datei.arbeitsfassung()).controlName("arbeitsfassungen")
                        .fileName("arbeit.jpg").mimeType("image/jpeg").build());
            }
        }
        if (mitArbeitsfassung) {
            spec = spec.multiPart("zusammenfassen", "true");
        }
        stand(mandantCode, dateien);
        gesendet = dateien;
        rohAntwort = null;
        antwort = spec.when().post(PFAD);
        merken(mandantCode);
    }

    @Angenommen("das Dokument zur Datei «{}» ist verworfen")
    public void verworfen(String name) throws SQLException {
        UUID id = dokumentVon.get(name);
        String schema = new MandantCode(mandant).schema();
        ausfuehren("update " + schema + ".dokument_datei set status = 'VERWORFEN' where dokument_id = ?::uuid",
                id.toString());
        ausfuehren("update " + schema + ".dokument set status = 'VERWORFEN' where id = ?::uuid", id.toString());
    }

    @Angenommen("der Object Storage fällt nach {int} abgelegten Datei(en) aus \\({word})")
    public void stoerung(int erlaubt, String art) {
        S3Stoerung.ab(erlaubt, S3Stoerung.Art.valueOf(art));
    }

    @Wenn("«{word}» eine Anfrage über {int} MB ankündigt")
    public void zuGross(String benutzer, int megabyte) throws IOException, InterruptedException {
        long laenge = megabyte * 1024L * 1024L;
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(basis() + PFAD))
                .header("Authorization", "Bearer " + Testanmeldung.token(benutzer))
                .header("Content-Type", "multipart/form-data; boundary=grenze")
                .expectContinue(true)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.fromPublisher(nullen(laenge), laenge))
                .build();
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            rohAntwort = client.send(anfrage, HttpResponse.BodyHandlers.ofString());
        }
    }

    @Dann("antwortet der Upload mit Status {int} und diesen Ergebnissen:")
    public void ergebnisse(int status, DataTable tabelle) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
        assertThat(antwort.contentType()).startsWith("application/json");
        pruefeErgebnisse(antwort.jsonPath().getList(""), tabelle.asMaps());
    }

    @Dann("antwortet der Upload mit Status {int}, Fehlercode «{word}» und diesen Ergebnissen:")
    public void problemMitErgebnissen(int status, String fehlercode, DataTable tabelle) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
        assertThat(antwort.contentType()).startsWith("application/problem+json");
        Map<String, Object> inhalt = antwort.jsonPath().getMap("");
        assertThat(inhalt).containsOnlyKeys("type", "title", "status", "detail", "instance", "fehlercode",
                "ergebnisse");
        assertThat(inhalt.get("fehlercode")).isEqualTo(fehlercode);
        assertThat(inhalt.get("type")).isEqualTo("urn:urinet:archiv:fehler:" + fehlercode);
        assertThat((String) inhalt.get("detail")).isNotBlank();
        pruefeErgebnisse(antwort.jsonPath().getList("ergebnisse"), tabelle.asMaps());
    }

    @Dann("antwortet der Upload mit Status {int} und Fehlercode «{word}»")
    public void problem(int status, String fehlercode) {
        if (rohAntwort != null) {
            assertThat(rohAntwort.statusCode()).as(rohAntwort.body()).isEqualTo(status);
            assertThat(rohAntwort.headers().firstValue("Content-Type")).hasValueSatisfying(
                    t -> assertThat(t).startsWith("application/problem+json"));
            assertThat(rohAntwort.body()).contains("\"fehlercode\":\"" + fehlercode + "\"");
            return;
        }
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
        assertThat(antwort.contentType()).startsWith("application/problem+json");
        Map<String, Object> inhalt = antwort.jsonPath().getMap("");
        assertThat(inhalt).containsOnlyKeys("type", "title", "status", "detail", "instance", "fehlercode");
        assertThat(inhalt.get("fehlercode")).isEqualTo(fehlercode);
        assertThat((String) inhalt.get("detail")).isNotBlank();
    }

    @Dann("verlangt die Antwort einen neuen Versuch nach {int} Sekunden")
    public void retryAfter(int sekunden) {
        assertThat(antwort.header("Retry-After")).isEqualTo(String.valueOf(sekunden));
    }

    @Dann("ist im Mandanten «{word}» nichts gespeichert")
    public void nichtsGespeichert(String mandantCode) throws SQLException {
        Stand nachher = zaehlen(mandantCode, gesendet);
        assertThat(nachher.dokumente()).as("Dokumente").isEqualTo(vorher.dokumente());
        assertThat(nachher.dateien()).as("Dateien").isEqualTo(vorher.dateien());
        assertThat(nachher.auftraege()).as("Aufträge VERARBEITEN").isEqualTo(vorher.auftraege());
        assertThat(nachher.eingang()).as("Versionen unter eingang/").containsExactlyInAnyOrderElementsOf(vorher.eingang());
        assertThat(nachher.zeilenJeSha()).as("Zeilen in dokument_datei je gesendeter Prüfsumme")
                .isEqualTo(vorher.zeilenJeSha());
    }

    @Dann("steht jedes neue Dokument im Status «NEU» als «PERSONAL» mit Quelle «{word}» und Erfasser «{word}»")
    public void dokumenteNeu(String quelle, String erfasser) throws SQLException {
        assertThat(neueIds()).isNotEmpty();
        for (UUID id : neueIds()) {
            Map<String, String> zeile = abfragen("select status, vertraulichkeit, quelle, erstellt_von, art from "
                    + schema() + ".dokument where id = ?::uuid", id.toString()).getFirst();
            assertThat(zeile).containsEntry("status", "NEU").containsEntry("vertraulichkeit", "PERSONAL")
                    .containsEntry("quelle", quelle).containsEntry("erstellt_von", Testanmeldung.sub(erfasser))
                    .containsEntry("art", null);
        }
    }

    @Dann("hält das Archiv zu jeder neuen Datei die SHA-256 der gesendeten Bytes mit Empfangszeitpunkt fest")
    public void pruefsummen() throws SQLException {
        for (Datei datei : angenommen()) {
            List<Map<String, String>> zeilen = abfragen("select dokument_id, rolle, sha256, empfangen, s3_version, "
                    + "s3_schluessel, groesse, status from " + schema() + ".dokument_datei where sha256 = ? and rolle = "
                    + "'ORIGINAL'", sha256(datei.inhalt()));
            assertThat(zeilen).as(datei.name()).singleElement().satisfies(z -> {
                assertThat(z.get("empfangen")).isNotNull();
                assertThat(z.get("s3_version")).isNotBlank();
                assertThat(z.get("status")).isEqualTo("AKTIV");
                assertThat(Long.parseLong(z.get("groesse"))).isEqualTo(datei.inhalt().length);
            });
        }
    }

    @Dann("liegt jede neue Datei unverändert unter «eingang\\/» im Bucket von «{word}»")
    public void imEingang(String mandantCode) throws SQLException {
        for (Datei datei : angenommen()) {
            Map<String, String> zeile = abfragen("select dokument_id, s3_schluessel, s3_version from " + schema()
                    + ".dokument_datei where sha256 = ? and rolle = 'ORIGINAL'", sha256(datei.inhalt())).getFirst();
            assertThat(zeile.get("s3_schluessel")).startsWith("eingang/").contains("/" + zeile.get("dokument_id") + "/");
            assertThat(TestAblage.lesen(mandantCode, zeile.get("s3_schluessel"), zeile.get("s3_version")))
                    .as(datei.name()).isEqualTo(datei.inhalt());
        }
    }

    @Dann("ist für jedes neue Dokument genau ein Auftrag «VERARBEITEN» eingereiht")
    public void auftraege() throws SQLException {
        for (UUID id : neueIds()) {
            assertThat(abfragen("select status from public.auftrag where typ = 'VERARBEITEN' and mandant = ? "
                    + "and nutzlast->>'dokumentId' = ?", mandant, id.toString())).as(id.toString()).singleElement()
                    .satisfies(z -> assertThat(z.get("status")).isEqualTo("OFFEN"));
        }
    }

    @Dann("ist der Auftrag «VERARBEITEN» jedes neuen Dokuments «FERTIG»")
    public void verarbeitet() throws SQLException {
        assertThat(neueIds()).isNotEmpty();
        for (UUID id : neueIds()) {
            assertThat(abfragen("select status from public.auftrag where typ = 'VERARBEITEN' and mandant = ? "
                    + "and nutzlast->>'dokumentId' = ?", mandant, id.toString())).as(id.toString()).singleElement()
                    .satisfies(z -> assertThat(z.get("status")).isEqualTo("FERTIG"));
        }
    }

    @Dann("steht jedes neue Dokument im Status «EINGANGSKORB» mit PDF\\/A und Volltext")
    public void inVerarbeitung() throws SQLException {
        for (UUID id : neueIds()) {
            Map<String, String> zeile = abfragen("select status, s3_pdfa, sha256_pdfa, volltext from " + schema()
                    + ".dokument where id = ?::uuid", id.toString()).getFirst();
            assertThat(zeile).containsEntry("status", "EINGANGSKORB");
            assertThat(zeile.get("s3_pdfa")).startsWith("eingang/").endsWith("/" + id + "/pdfa.pdf");
            assertThat(zeile.get("sha256_pdfa")).isNotBlank();
            assertThat(zeile.get("volltext")).contains("Testrechnung Upload");
        }
    }

    @Dann("ist für jedes neue Dokument genau ein Auftrag «KI_VORSCHLAG» eingereiht")
    public void kiVorschlag() throws SQLException {
        for (UUID id : neueIds()) {
            assertThat(abfragen("select status from public.auftrag where typ = 'KI_VORSCHLAG' and mandant = ? "
                    + "and nutzlast->>'dokumentId' = ?", mandant, id.toString())).as(id.toString()).hasSize(1);
        }
    }

    @Dann("nennen alle Ergebnisse dasselbe Dokument mit dem Hinweis {string}")
    public void dasselbeDokument(String hinweis) throws SQLException {
        assertThat(neueIds()).hasSize(1);
        assertThat(abfragen("select hinweis from " + schema() + ".dokument where id = ?::uuid",
                neueIds().getFirst().toString()).getFirst().get("hinweis")).isEqualTo(hinweis);
    }

    @Dann("hat das Dokument die Originale in der gesendeten Reihenfolge")
    public void reihenfolge() throws SQLException {
        List<Map<String, String>> zeilen = abfragen("select nr, sha256 from " + schema() + ".dokument_datei where "
                + "dokument_id = ?::uuid and rolle = 'ORIGINAL' order by nr", neueIds().getFirst().toString());
        assertThat(zeilen.stream().map(z -> z.get("sha256")).toList())
                .containsExactlyElementsOf(angenommen().stream().map(d -> sha256(d.inhalt())).toList());
        assertThat(zeilen.stream().map(z -> z.get("nr")).toList())
                .containsExactlyElementsOf(Stream.iterate(1, i -> i + 1).limit(zeilen.size()).map(String::valueOf)
                        .toList());
    }

    @Dann("hat das Dokument je Seite das Original und die Arbeitsfassung unter «eingang\\/»")
    public void arbeitsfassungen() throws SQLException {
        UUID id = neueIds().getFirst();
        int nr = 1;
        for (Datei datei : angenommen()) {
            for (String rolle : List.of("ORIGINAL", "ARBEITSFASSUNG")) {
                byte[] erwartet = "ORIGINAL".equals(rolle) ? datei.inhalt() : datei.arbeitsfassung();
                Map<String, String> zeile = abfragen("select sha256, s3_schluessel, s3_version, mime from " + schema()
                        + ".dokument_datei where dokument_id = ?::uuid and rolle = ? and nr = " + nr, id.toString(),
                        rolle).getFirst();
                assertThat(zeile.get("sha256")).as(rolle + " " + nr).isEqualTo(sha256(erwartet));
                assertThat(zeile.get("s3_schluessel")).startsWith("eingang/");
                assertThat(TestAblage.lesen(mandant, zeile.get("s3_schluessel"), zeile.get("s3_version")))
                        .isEqualTo(erwartet);
                if ("ARBEITSFASSUNG".equals(rolle)) {
                    assertThat(zeile.get("mime")).isEqualTo("image/jpeg");
                    assertThat(zeile.get("s3_schluessel")).endsWith("/" + nr + "-arbeitsfassung.jpg");
                }
            }
            nr++;
        }
    }

    @Dann("nennt das Ergebnis von «{}» das vorhandene Dokument")
    public void vorhandenId(String name) {
        Map<String, Object> ergebnis = ergebnis(name);
        assertThat(ergebnis).containsEntry("status", "DUBLETTE")
                .containsEntry("vorhandenId", dokumentVon.get(name).toString()).doesNotContainKey("meldung");
    }

    @Dann("meldet das Ergebnis von «{}» nur «bereits vorhanden» ohne ID")
    public void ohneId(String name) {
        Map<String, Object> ergebnis = ergebnis(name);
        assertThat(ergebnis).containsEntry("status", "DUBLETTE").containsEntry("meldung", "bereits vorhanden")
                .doesNotContainKeys("vorhandenId", "id");
        assertThat(antwort.asString()).doesNotContain(dokumentVon.get(name).toString());
    }

    @Dann("ist das Ergebnis von «{}» ein neues Dokument")
    public void neuesDokument(String name) {
        Map<String, Object> ergebnis = ergebnis(name);
        assertThat(ergebnis).containsEntry("status", "NEU");
        assertThat(ergebnis.get("id")).isNotEqualTo(dokumentVon.get(name).toString());
    }

    @Dann("hält das Log zu jeder gesendeten Datei Mandant, Prüfsumme, Grösse und Ergebnis fest, ohne Dateinamen:")
    public void spurImLog(DataTable tabelle) {
        List<String> zeilen = mitschnitt.eintraege().stream()
                .filter(eintrag -> DokumentErfassen.class.getName().equals(eintrag.getLoggerName()))
                .map(mitschnitt::nachricht).toList();
        String mandantWert = "mandant=" + mandant + " ";
        for (Map<String, String> zeile : tabelle.asMaps()) {
            Datei datei = gesendet.stream().filter(d -> d.name().equals(zeile.get("datei"))).findFirst().orElseThrow();
            String sha = "sha256=" + sha256(datei.inhalt());
            assertThat(zeilen).as(datei.name() + " empfangen").filteredOn(z -> z.startsWith("Datei empfangen:"))
                    .filteredOn(z -> z.contains(sha)).singleElement().asString().contains(mandantWert)
                    .contains("rolle=ORIGINAL").endsWith("bytes=" + datei.inhalt().length);
            assertThat(zeilen).as(datei.name() + " Ergebnis").filteredOn(z -> !z.startsWith("Datei empfangen:"))
                    .filteredOn(z -> z.contains(sha)).singleElement().asString().contains(mandantWert)
                    .contains("ergebnis=" + zeile.get("ergebnis"));
        }
        String log = mitschnitt.volltext();
        for (Datei datei : gesendet) {
            assertThat(log).as("Log").doesNotContain(datei.name());
        }
    }

    @Dann("bleiben keine temporären Dateien des Uploads zurück")
    public void keineTempDateien() throws InterruptedException {
        long frist = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        Set<Path> neu = tempDateien();
        while (!tempVorher.containsAll(neu) && System.nanoTime() < frist) {
            Thread.sleep(50);
            neu = tempDateien();
        }
        neu.removeAll(tempVorher);
        assertThat(neu).isEmpty();
    }

    @Wenn("«{word}» gleichzeitig in {int} Anfragen je eine Datei von {int} MB hochlädt")
    public void gleichzeitigHochladen(String benutzer, int anzahl, int megabyte) throws Exception {
        String mandantCode = benutzer.substring(benutzer.indexOf('-') + 1);
        String token = Testanmeldung.token(benutzer);
        List<List<Datei>> anfragen = new ArrayList<>();
        for (int i = 1; i <= anzahl; i++) {
            anfragen.add(List.of(new Datei("parallel-" + i + ".pdf", inhalt("parallel-" + i + ".pdf",
                    "pdf:" + megabyte), null)));
        }
        stand(mandantCode, anfragen.stream().flatMap(List::stream).toList());
        arbeitVorher = arbeitsDateien();
        beobachtet.clear();
        CyclicBarrier start = new CyclicBarrier(anzahl);
        AtomicBoolean laeuft = new AtomicBoolean(true);
        try (ExecutorService pool = Executors.newFixedThreadPool(anzahl + 1)) {
            Future<?> beobachter = pool.submit(() -> {
                while (laeuft.get()) {
                    beobachtet.addAll(tempDateien());
                    Thread.sleep(5);
                }
                return null;
            });
            List<Future<Response>> laeufe = new ArrayList<>();
            for (List<Datei> dateien : anfragen) {
                laeufe.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return multipart(given().auth().oauth2(token), dateien, false, null).when().post(PFAD);
                }));
            }
            List<Response> antworten = new ArrayList<>();
            for (Future<Response> lauf : laeufe) {
                antworten.add(lauf.get(5, TimeUnit.MINUTES));
            }
            laeuft.set(false);
            beobachter.get(30, TimeUnit.SECONDS);
            parallel = antworten;
        }
        gesendet = anfragen.stream().flatMap(List::stream).toList();
        for (Response einzeln : parallel) {
            antwort = einzeln;
            merken(mandantCode);
        }
    }

    @Dann("antworten alle Anfragen mit Status 202 und je einem neuen Dokument im Status «NEU»")
    public void alleParallelAngenommen() throws SQLException {
        assertThat(parallel).isNotEmpty().allSatisfy(einzeln -> {
            assertThat(einzeln.statusCode()).as(einzeln.asString()).isEqualTo(202);
            assertThat(einzeln.jsonPath().getList("status", String.class)).containsExactly("NEU");
        });
        assertThat(parallel.stream().map(e -> e.jsonPath().getString("[0].id")).distinct()).hasSize(parallel.size());
        for (Datei datei : gesendet) {
            assertThat(abfragen("select groesse from " + schema() + ".dokument_datei where sha256 = ? and rolle = "
                    + "'ORIGINAL'", sha256(datei.inhalt()))).as(datei.name()).singleElement()
                    .satisfies(z -> assertThat(Long.parseLong(z.get("groesse"))).isEqualTo(datei.inhalt().length));
        }
    }

    @Dann("lagen die Teile der Uploads im Arbeitsverzeichnis, nicht im temporären Verzeichnis der JVM")
    public void teileImArbeitsverzeichnis() {
        assertThat(TEMP.toAbsolutePath().normalize()).startsWith(ARBEIT.toAbsolutePath().normalize());
        Path jvmTemp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        assertThat(ARBEIT.toAbsolutePath().normalize().startsWith(jvmTemp)).as("unter java.io.tmpdir").isFalse();
        assertThat(beobachtet).as("Teile im Upload-Verzeichnis während der Anfragen").isNotEmpty();
    }

    @Dann("bleibt im Arbeitsverzeichnis keine Datei zurück")
    public void arbeitsverzeichnisLeer() throws InterruptedException {
        long frist = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Set<Path> neu = arbeitsDateien();
        while (!arbeitVorher.containsAll(neu) && System.nanoTime() < frist) {
            Thread.sleep(50);
            neu = arbeitsDateien();
        }
        neu.removeAll(arbeitVorher);
        assertThat(neu).isEmpty();
    }

    /** Reguläre Dateien im ganzen Arbeitsverzeichnis (Upload, OCR, Ablage). */
    private static Set<Path> arbeitsDateien() {
        if (!Files.isDirectory(ARBEIT)) {
            return new LinkedHashSet<>();
        }
        try (Stream<Path> dateien = Files.walk(ARBEIT)) {
            return new LinkedHashSet<>(dateien.filter(Files::isRegularFile).toList());
        } catch (IOException | UncheckedIOException e) {
            // eine Datei verschwand während des Durchlaufs: nochmals
            return arbeitsDateien();
        }
    }

    private void senden(String benutzer, List<Datei> dateien, boolean zusammenfassen, String hinweis) {
        String mandantCode = benutzer.substring(benutzer.indexOf('-') + 1);
        stand(mandantCode, dateien);
        gesendet = dateien;
        tempVorher = tempDateien();
        rohAntwort = null;
        antwort = multipart(given().auth().oauth2(Testanmeldung.token(benutzer)), dateien, zusammenfassen, hinweis)
                .when().post(PFAD);
        merken(mandantCode);
    }

    private void stand(String mandantCode, List<Datei> dateien) {
        mandant = mandantCode;
        try {
            vorher = zaehlen(mandantCode, dateien);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RequestSpecification multipart(RequestSpecification anfrage, List<Datei> dateien,
                                                  boolean zusammenfassen, String hinweis) {
        RequestSpecification spec = anfrage.config(UTF8).contentType("multipart/form-data; charset=UTF-8");
        for (Datei datei : dateien) {
            spec = spec.multiPart(new MultiPartSpecBuilder(datei.inhalt()).controlName("dateien")
                    .fileName(datei.name()).mimeType("application/octet-stream")
                    .build());
        }
        for (Datei datei : dateien) {
            if (datei.arbeitsfassung() != null) {
                spec = spec.multiPart(new MultiPartSpecBuilder(datei.arbeitsfassung()).controlName("arbeitsfassungen")
                        .fileName("arbeit.jpg").mimeType("image/jpeg").build());
            }
        }
        if (zusammenfassen) {
            spec = spec.multiPart("zusammenfassen", "true");
        }
        if (hinweis != null) {
            spec = spec.multiPart(new MultiPartSpecBuilder(hinweis).controlName("hinweis")
                    .charset(StandardCharsets.UTF_8).build());
        }
        return spec;
    }

    /** Merkt die neuen Dokumente aus der Antwort fürs Aufräumen. */
    private void merken(String mandantCode) {
        List<Map<String, Object>> liste = liste();
        if (liste == null) {
            return;
        }
        liste.stream().map(e -> e.get("id")).filter(String.class::isInstance)
                .forEach(id -> angelegt.computeIfAbsent(mandantCode, m -> new LinkedHashSet<>())
                        .add(UUID.fromString((String) id)));
    }

    private List<Map<String, Object>> liste() {
        if (antwort.statusCode() == 202) {
            return antwort.jsonPath().getList("");
        }
        if (antwort.contentType().startsWith("application/problem+json")) {
            return antwort.jsonPath().getList("ergebnisse");
        }
        return null;
    }

    private List<UUID> neueIds() {
        return liste().stream().map(e -> e.get("id")).filter(String.class::isInstance)
                .map(id -> UUID.fromString((String) id)).distinct().toList();
    }

    private List<Datei> angenommen() {
        List<Map<String, Object>> liste = liste();
        List<Datei> dateien = new ArrayList<>();
        for (int i = 0; i < gesendet.size(); i++) {
            if ("NEU".equals(liste.get(i).get("status"))) {
                dateien.add(gesendet.get(i));
            }
        }
        return dateien;
    }

    private Map<String, Object> ergebnis(String name) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(202);
        return liste().stream().filter(e -> name.equals(e.get("datei"))).findFirst().orElseThrow();
    }

    private void pruefeErgebnisse(List<Map<String, Object>> liste, List<Map<String, String>> erwartet) {
        assertThat(liste).as(antwort.asString()).hasSize(erwartet.size());
        for (int i = 0; i < erwartet.size(); i++) {
            Map<String, String> soll = erwartet.get(i);
            Map<String, Object> ist = liste.get(i);
            String fall = "Ergebnis " + (i + 1);
            assertThat(ist.get("datei")).as(fall).isEqualTo(ohneVerboteneZeichen(name(soll.get("datei"))));
            assertThat(ist.get("status")).as(fall).isEqualTo(soll.get("status"));
            switch (soll.get("status")) {
                case "NEU" -> assertThat(ist).as(fall).containsKey("id").doesNotContainKeys("vorhandenId", "fehler");
                case "DUBLETTE" -> assertThat(ist).as(fall).doesNotContainKeys("id", "fehler");
                case "ABGELEHNT" -> {
                    assertThat(ist).as(fall).doesNotContainKeys("id", "vorhandenId");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fehler = (Map<String, Object>) ist.get("fehler");
                    assertThat(fehler).as(fall).containsEntry("fehlercode", soll.get("fehlercode"))
                            .containsEntry("status", Integer.parseInt(soll.get("http")));
                    assertThat((String) fehler.get("detail")).as(fall).isNotBlank();
                }
                default -> throw new IllegalArgumentException(soll.get("status"));
            }
        }
    }

    private List<Datei> dateien(DataTable tabelle) {
        List<Datei> dateien = new ArrayList<>();
        for (Map<String, String> zeile : tabelle.asMaps()) {
            String name = name(zeile.get("datei"));
            String arbeit = zeile.get("arbeitsfassung");
            dateien.add(new Datei(name, inhalt(name, zeile.get("typ")),
                    arbeit == null ? null : Testdateien.erzeugen(arbeit)));
        }
        return dateien;
    }

    /**
     * {@code {U+XXXX}} im Namen steht für das Zeichen mit diesem Code (z. B. die unsichtbare Richtungsumkehr
     * U+202E), {@code {n×z}} für n-mal das Zeichen z, {@code Leerzeichen} für ein Leerzeichen.
     */
    static String name(String roh) {
        String name = ZEICHEN.matcher(roh).replaceAll(m -> Character.toString(Integer.parseInt(m.group(1), 16)));
        return WIEDERHOLT.matcher(name).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                ("Leerzeichen".equals(m.group(2)) ? " " : m.group(2)).repeat(Integer.parseInt(m.group(1)))));
    }

    /** Die Anzeige eines Namens mit verbotenen Zeichen enthält diese nicht (Req 3.10). */
    private static String ohneVerboteneZeichen(String name) {
        return name.replaceAll("[\\p{Cc}\\p{Cs}\\p{Zl}\\p{Zp}\\p{Cf}&&[^\\u200C\\u200D]]", "");
    }

    @Dann("hat jedes neue Dokument den Dateinamen «{}» und keinen Titel")
    public void dateiname(String erwartet) throws SQLException {
        String name = name(erwartet);
        assertThat(neueIds()).isNotEmpty();
        for (UUID id : neueIds()) {
            Map<String, String> zeile = abfragen("select dateiname, titel from " + schema()
                    + ".dokument where id = ?::uuid", id.toString()).getFirst();
            assertThat(zeile.get("dateiname")).as("dateiname").isEqualTo(name.isEmpty() ? null : name);
            assertThat(zeile.get("titel")).as("titel").isNull();
        }
    }

    @Dann("liefert die Ansicht des neuen Dokuments für «{word}» den Dateinamen «{}» und keinen Titel")
    public void dateinameInDerAnsicht(String benutzer, String erwartet) {
        assertThat(neueIds()).hasSize(1);
        Response ansicht = given().auth().oauth2(Testanmeldung.token(benutzer)).when()
                .get(PFAD + "/" + neueIds().getFirst());
        assertThat(ansicht.statusCode()).as(ansicht.asString()).isEqualTo(200);
        assertThat(ansicht.jsonPath().getString("dateiname")).isEqualTo(name(erwartet));
        assertThat(ansicht.jsonPath().getString("titel")).isNull();
    }

    @Dann("trägt kein Dokument im Mandanten «{word}» den Dateinamen «{}» oder «{}»")
    public void keinDateiname(String mandantCode, String erster, String zweiter) throws SQLException {
        for (String teil : List.of(name(erster), name(zweiter))) {
            assertThat(abfragen("select id from " + new MandantCode(mandantCode).schema() + ".dokument where "
                    + "strpos(dateiname, ?) > 0", teil)).as(teil).isEmpty();
        }
    }

    @Wenn("«{word}» das neue Dokument ansieht und seine Datei öffnet")
    public void ansehenUndOeffnen(String benutzer) {
        assertThat(neueIds()).hasSize(1);
        String token = Testanmeldung.token(benutzer);
        Response ansicht = given().auth().oauth2(token).when().get(PFAD + "/" + neueIds().getFirst());
        assertThat(ansicht.statusCode()).as(ansicht.asString()).isEqualTo(200);
        Response umleitung = given().auth().oauth2(token).redirects().follow(false).when()
                .get(PFAD + "/" + neueIds().getFirst() + "/datei");
        Response json = given().auth().oauth2(token).redirects().follow(false).accept("application/json").when()
                .get(PFAD + "/" + neueIds().getFirst() + "/datei");
        assertThat(umleitung.statusCode()).as(umleitung.asString()).isEqualTo(302);
        assertThat(json.statusCode()).as(json.asString()).isEqualTo(200);
        geoeffnet = List.of(antwort, ansicht, umleitung, json);
    }

    @Dann("taucht «{}» weder im Log noch im Zugriffsprotokoll, in Nutzlasten von Aufträgen, S3-Schlüsseln oder "
            + "Antwort-Headern auf")
    public void nirgends(String teil) throws SQLException {
        assertThat(mitschnitt.volltext()).as("Log").doesNotContain(teil);
        assertThat(abfragen("select id from " + schema() + ".zugriff_protokoll where strpos(coalesce(details::text, "
                + "''), ?) > 0", teil)).as("Zugriffsprotokoll").isEmpty();
        assertThat(abfragen("select id from public.auftrag where strpos(nutzlast::text, ?) > 0", teil))
                .as("Nutzlasten der Aufträge").isEmpty();
        assertThat(abfragen("select count(*) as anzahl from public.auftrag where nutzlast->>'dokumentId' = ?",
                neueIds().getFirst().toString()).getFirst().get("anzahl")).as("Aufträge des Dokuments")
                .isNotEqualTo("0");
        assertThat(TestAblage.versionen(mandant, "")).as("S3-Schlüssel").isNotEmpty()
                .noneMatch(schluessel -> schluessel.contains(teil));
        for (Response r : geoeffnet) {
            assertThat(r.headers().asList()).as("Header").noneMatch(h -> h.getValue().contains(teil)
                    || h.getName().contains(teil));
        }
        assertThat(geoeffnet.get(1).jsonPath().getString("dateiname")).as("Feld dateiname").contains(teil);
        assertThat(geoeffnet.get(3).asString()).as("vorsignierte Adresse").doesNotContain(teil);
    }

    /** Typ aus {@link Testdateien}, {@code wie:<name>} für dieselben Bytes oder {@code pdf:<MB>} für eine Grösse. */
    private byte[] inhalt(String name, String typ) {
        byte[] inhalt;
        if (typ.startsWith("wie:")) {
            inhalt = inhalte.get(typ.substring(4));
        } else if (typ.startsWith("pdf:")) {
            inhalt = Testdateien.pdf(Long.parseLong(typ.substring(4)) * 1024 * 1024);
        } else {
            inhalt = Testdateien.erzeugen(typ);
        }
        inhalte.putIfAbsent(name, inhalt);
        return inhalt;
    }

    private Stand zaehlen(String mandantCode, List<Datei> dateien) throws SQLException {
        String schema = new MandantCode(mandantCode).schema();
        Map<String, List<String>> zeilenJeSha = new LinkedHashMap<>();
        for (Datei datei : dateien) {
            for (byte[] bytes : Stream.of(datei.inhalt(), datei.arbeitsfassung()).filter(b -> b != null).toList()) {
                String sha = sha256(bytes);
                zeilenJeSha.put(sha, abfragen("select dokument_id || '/' || rolle || '/' || nr as zeile from " + schema
                        + ".dokument_datei where sha256 = ? order by 1", sha).stream().map(z -> z.get("zeile"))
                        .toList());
            }
        }
        return new Stand(zahl("select count(*) from " + schema + ".dokument"),
                zahl("select count(*) from " + schema + ".dokument_datei"),
                zahl("select count(*) from public.auftrag where typ = 'VERARBEITEN' and mandant = '" + mandantCode
                        + "'"),
                TestAblage.versionen(mandantCode, "eingang/"), zeilenJeSha);
    }

    private String schema() {
        return new MandantCode(mandant).schema();
    }

    private static Set<Path> tempDateien() {
        if (!Files.isDirectory(TEMP)) {
            return new LinkedHashSet<>();
        }
        try (Stream<Path> dateien = Files.list(TEMP)) {
            return new LinkedHashSet<>(dateien.toList());
        } catch (IOException | UncheckedIOException e) {
            if (Files.isDirectory(TEMP)) {
                // eine Datei verschwand während des Durchlaufs: nochmals
                return tempDateien();
            }
            return new LinkedHashSet<>();
        }
    }

    private static String sha256(byte[] inhalt) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inhalt));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String basis() {
        return "http://localhost:" + ConfigProvider.getConfig().getValue("quarkus.http.test-port", Integer.class);
    }

    /** Liefert {@code laenge} Nullbytes erst auf Abruf; bei 413 vor dem Senden fragt der Client keine ab. */
    private static Flow.Publisher<ByteBuffer> nullen(long laenge) {
        return abonnent -> abonnent.onSubscribe(new Flow.Subscription() {
            private long rest = laenge;
            private boolean ende;

            @Override
            public synchronized void request(long n) {
                for (long i = 0; i < n && rest > 0 && !ende; i++) {
                    int stueck = (int) Math.min(64 * 1024, rest);
                    rest -= stueck;
                    abonnent.onNext(ByteBuffer.allocate(stueck));
                }
                if (rest == 0 && !ende) {
                    ende = true;
                    abonnent.onComplete();
                }
            }

            @Override
            public synchronized void cancel() {
                ende = true;
            }
        });
    }

    private long zahl(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql);
             ResultSet zeile = befehl.executeQuery()) {
            zeile.next();
            return zeile.getLong(1);
        }
    }

    private void ausfuehren(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                if (werte[i] instanceof String[] liste) {
                    befehl.setArray(i + 1, verbindung.createArrayOf("text", liste));
                } else {
                    befehl.setObject(i + 1, werte[i]);
                }
            }
            befehl.executeUpdate();
        }
    }

    private List<Map<String, String>> abfragen(String sql, String... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                befehl.setString(i + 1, werte[i]);
            }
            List<Map<String, String>> zeilen = new ArrayList<>();
            try (ResultSet zeile = befehl.executeQuery()) {
                int spalten = zeile.getMetaData().getColumnCount();
                while (zeile.next()) {
                    Map<String, String> werteDerZeile = new LinkedHashMap<>();
                    for (int s = 1; s <= spalten; s++) {
                        werteDerZeile.put(zeile.getMetaData().getColumnLabel(s), zeile.getString(s));
                    }
                    zeilen.add(werteDerZeile);
                }
            }
            return zeilen;
        }
    }
}
