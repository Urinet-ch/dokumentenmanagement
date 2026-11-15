package ch.urinet.archiv.integration;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.sicherheit.adapter.in.kommando.WebseitenSchluesselKommando;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
import ch.urinet.archiv.sicherheit.application.HashSperre;
import ch.urinet.archiv.sicherheit.application.WebseitenSchluesselVerwaltung;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zur Webseiten-Schnittstelle unter {@code /public/} (Spec 03, Aufgabe 11.6): Anmeldung, Problem Details und
 * Header gegen die Probe {@code /public/v1/probe} ({@code ch.urinet.archiv.probe.ProbeWebseiteResource}). Webseiten-
 * Schlüssel und gewöhnliche API-Tokens entstehen zur Laufzeit und werden danach gelöscht.
 */
public class WebseitenSchritte {

    @Inject
    ApiTokenVerwaltung verwaltung;

    @Inject
    WebseitenSchluesselVerwaltung webseitenVerwaltung;

    @Inject
    WebseitenSchluesselKommando webseitenKommando;

    @Inject
    HashSperre hashSperre;

    @Inject
    @ConfigProperty(name = "archiv.webseite.absender-datei")
    Path absenderDatei;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final SecureRandom zufall = new SecureRandom();
    private final List<String> tokenIds = new ArrayList<>();
    private final List<Response> abgelehnt = new ArrayList<>();
    private String aktiv;
    private String widerrufen;
    private String webseitenToken;
    private String erfassungsToken;
    private String absenderToken;
    private String alterSchluessel;
    private String neuerSchluessel;
    private String kommandoStdout;
    private String kommandoStderr;
    private String kommandoLog;
    private Response antwort;
    private CountDownLatch hashFreigabe;
    private ExecutorService hashExecutor;
    private List<CompletableFuture<Void>> hashBesetzer = List.of();

    @Angenommen("für «urinet» gibt es einen aktiven und einen widerrufenen Webseiten-Lese-Schlüssel")
    public void tokens() {
        aktiv = anlegen("aktiv", "lesen");
        widerrufen = anlegen("widerrufen", "lesen");
        verwaltung.widerrufen(new MandantCode("urinet"), ApiToken.lesen(widerrufen).orElseThrow().id(),
                Optional.empty());
    }

    @Wenn("«{}» ohne Anmeldung, mit «ua_kaputt», mit falschem Geheimnis und mit dem widerrufenen Token abgefragt wird")
    public void abgelehnt(String pfad) {
        String falsch = ApiToken.PRAEFIX + ApiToken.lesen(aktiv).orElseThrow().id() + "_"
                + ApiToken.neu(zufall).geheimnis();
        abgelehnt.add(given().when().get(pfad));
        for (String wert : List.of("ua_kaputt", falsch, widerrufen)) {
            abgelehnt.add(given().header("Authorization", "Bearer " + wert).when().get(pfad));
        }
    }

    @Dann("ist jede Antwort 401 «NICHT_ANGEMELDET» mit «WWW-Authenticate: Bearer»")
    public void jede401() {
        assertThat(abgelehnt).hasSize(4).allSatisfy(a -> {
            assertThat(ProblemDetails.pruefen(a, 401)).isEqualTo("NICHT_ANGEMELDET");
            assertThat(a.header("WWW-Authenticate")).isEqualTo("Bearer");
        });
    }

    @Dann("unterscheiden sich die Antworten nur in «instance»")
    public void nurInstance() {
        Response erste = abgelehnt.getFirst();
        for (Response andere : abgelehnt) {
            assertThat(ohneInstance(andere)).isEqualTo(ohneInstance(erste));
            assertThat(kopfzeilen(andere)).isEqualTo(kopfzeilen(erste));
        }
    }

    @Wenn("«{word}» {word} «{}» aufruft")
    public void aufrufen(String benutzer, String methode, String pfad) {
        // ohne Kodierung durch REST-assured, damit «;» und «%» so ankommen, wie sie im Szenario stehen
        antwort = anmelden(benutzer).urlEncodingEnabled(false).when().request(methode, pfad);
    }

    @Wenn("«{word}» an «{}» {int} MB sendet")
    public void zuGross(String benutzer, String pfad, int mb) {
        // mit «Expect: 100-continue» liest der Client die frühe 413, statt den Rest in eine geschlossene Verbindung zu schreiben
        antwort = anmelden(benutzer).config(RestAssured.config().httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.protocol.expect-continue", true)))
                .contentType("text/plain").body(new byte[mb * 1024 * 1024]).when().post(pfad);
    }

    @Dann("antwortet die Webseiten-Schnittstelle mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("antwortet die Webseiten-Schnittstelle mit Status {int} und Fehlercode «{word}»")
    public void fehlercode(int status, String fehlercode) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(fehlercode);
    }

    @Angenommen("es gibt einen Webseiten-Lese-Schlüssel für «{}» mit höchster Stufe «{}»")
    public void leseSchluessel(String webseite, String stufe) {
        webseitenToken = erstellen(webseite, stufe, false);
    }

    @Angenommen("es gibt einen Webseiten-Lese-Schlüssel mit Absenderprüfung für «{}»")
    public void leseSchluesselMitAbsenderpruefung(String webseite) {
        absenderToken = erstellen(webseite, "oeffentlich", true);
    }

    @Angenommen("es gibt einen aktiven Webseiten-Lese-Schlüssel")
    public void aktiverLeseSchluessel() {
        webseitenToken = erstellen("probe", "oeffentlich", false);
    }

    @Wenn("«inhaber-urinet» die API-Tokens auflistet")
    public void apiTokensAuflisten() {
        antwort = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .when().get("/api/v1/api-tokens");
    }

    @Dann("enthält die Liste den Webseiten-Schlüssel «{}» mit höchster Stufe «{}»")
    public void listeEnthaeltWebseitenSchluessel(String webseite, String stufe) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        List<Map<String, Object>> clients = antwort.jsonPath().getList("");
        assertThat(clients).anySatisfy(client -> assertThat(client)
                .containsEntry("webseite", webseite)
                .containsEntry("hoechsteStufe", stufe)
                .containsEntry("scope", "webseite-lesen"));
        if (webseitenToken != null) {
            ApiToken token = ApiToken.lesen(webseitenToken).orElseThrow();
            assertThat(antwort.asString()).doesNotContain(token.geheimnis(), "geheimnis_hash");
        }
    }

    @Wenn("das Webseiten-Schlüssel-Kommando einen Lese-Schlüssel für «{}» erstellt")
    public void schluesselKommando(String webseite) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (LogMitschnitt log = LogMitschnitt.starten();
             PrintStream aus = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream fehler = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            int code = webseitenKommando.ausfuehren(
                    List.of("erstellen", "urinet", webseite, "lesen", "--stufe", "oeffentlich"), aus, fehler);
            assertThat(code).isZero();
            kommandoLog = log.volltext();
        }
        kommandoStdout = stdout.toString(StandardCharsets.UTF_8);
        kommandoStderr = stderr.toString(StandardCharsets.UTF_8);
        ApiToken token = ApiToken.lesen(kommandoStdout.strip()).orElseThrow();
        webseitenToken = kommandoStdout.strip();
        tokenIds.add(token.id());
    }

    @Dann("steht das Geheimnis nur einmal auf stdout und weder in stderr noch im Log")
    public void kommandoGeheimnisNurEinmal() {
        ApiToken token = ApiToken.lesen(kommandoStdout.strip()).orElseThrow();
        assertThat(kommandoStdout.lines()).hasSize(1);
        assertThat(kommandoStderr).contains(token.id(), "urinet", "probe", "webseite-lesen")
                .doesNotContain(token.geheimnis());
        assertThat(kommandoLog).contains(token.id(), "urinet", "probe", "webseite-lesen")
                .doesNotContain(token.geheimnis());
    }

    @Dann("steht in der Datenbank nur der Hash des Webseiten-Schlüssels")
    public void nurHashGespeichert() throws SQLException {
        ApiToken token = ApiToken.lesen(webseitenToken).orElseThrow();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select geheimnis_hash from public.api_client where id = ?")) {
            abfrage.setString(1, token.id());
            try (var ergebnis = abfrage.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                assertThat(ergebnis.getString(1)).startsWith("$argon2id$")
                        .doesNotContain(token.geheimnis());
            }
        }
    }

    @Wenn("«inhaber-urinet» über die API einen Webseiten-Schlüssel anlegen will")
    public void webseitenSchluesselApiAnlegen() {
        antwort = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .contentType("application/json")
                .body(Map.of("name", "Probe", "scope", "webseite-lesen"))
                .when().post("/api/v1/api-tokens");
    }

    @Dann("lehnt die API das mit Status {int} und Fehlercode «{word}» ab")
    public void apiFehler(int status, String fehlercode) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(fehlercode);
    }

    @Wenn("«inhaber-urinet» den Webseiten-Schlüssel widerruft")
    public void webseitenSchluesselWiderrufen() {
        String id = ApiToken.lesen(webseitenToken).orElseThrow().id();
        antwort = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .when().delete("/api/v1/api-tokens/" + id);
    }

    @Dann("antwortet die Token-Verwaltung mit Status {int}")
    public void tokenVerwaltungStatus(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Wenn("der Webseiten-Schlüssel mit einer Überlappung von {int} Sekunden rotiert wird")
    public void schluesselRotieren(int sekunden) {
        rotieren(Duration.ofSeconds(sekunden));
    }

    @Wenn("der Webseiten-Schlüssel ohne Überlappung rotiert wird")
    public void schluesselSofortRotieren() {
        rotieren(Duration.ZERO);
    }

    @Dann("erreicht der alte und der neue Webseiten-Schlüssel die Probe")
    public void beideSchluesselErreichenProbe() {
        assertThat(probe(alterSchluessel).statusCode()).isEqualTo(200);
        assertThat(probe(neuerSchluessel).statusCode()).isEqualTo(200);
    }

    @Dann("erhält der alte Webseiten-Schlüssel Status 401 und der neue erreicht die Probe")
    public void alterSchluesselAbgelaufen() {
        assertThat(ProblemDetails.pruefen(probe(alterSchluessel), 401)).isEqualTo("NICHT_ANGEMELDET");
        assertThat(probe(neuerSchluessel).statusCode()).isEqualTo(200);
    }

    @Angenommen("die Betriebskonfiguration erlaubt für «{}» das Netz «{}»")
    public void absenderNetzKonfigurieren(String webseite, String netz) throws IOException {
        Files.createDirectories(absenderDatei.getParent());
        Files.writeString(absenderDatei, webseite + "=" + netz + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    @Angenommen("die Betriebskonfiguration enthält keine Absendernetze")
    public void absenderNetzeEntfernen() throws IOException {
        Files.deleteIfExists(absenderDatei);
    }

    @Wenn("die Webseite mit Absender «{}» die Probe aufruft")
    public void absenderAufruf(String ip) {
        antwort = given().header("Authorization", "Bearer " + absenderToken)
                .header("X-Real-Ip", ip).when().get("/public/v1/probe");
    }

    @Dann("ist die Antwort 401 «NICHT_ANGEMELDET» mit «WWW-Authenticate: Bearer»")
    public void absenderNichtErlaubt() {
        assertThat(ProblemDetails.pruefen(antwort, 401)).isEqualTo("NICHT_ANGEMELDET");
        assertThat(antwort.header("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Wenn("die Webseite den Schlüssel erstmals an der Probe verwendet")
    public void schluesselErstmalsVerwenden() {
        antwort = probe(webseitenToken);
    }

    @Wenn("alle Plätze der HashSperre belegt sind")
    public void hashSperreBelegen() throws InterruptedException {
        int plaetze = ConfigProvider.getConfig().getValue("archiv.api-token.parallele-pruefungen", Integer.class);
        CountDownLatch bereit = new CountDownLatch(plaetze);
        hashFreigabe = new CountDownLatch(1);
        hashExecutor = Executors.newFixedThreadPool(plaetze);
        hashBesetzer = java.util.stream.IntStream.range(0, plaetze)
                .mapToObj(i -> CompletableFuture.runAsync(() -> hashSperre.ausfuehren(() -> {
                    bereit.countDown();
                    warten(hashFreigabe);
                    return null;
                }), hashExecutor))
                .toList();
        assertThat(bereit.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Wenn("ein ungültiger Webseiten-Schlüssel die Probe aufruft")
    public void ungueltigerWebseitenSchluessel() {
        antwort = probe(ApiToken.neu(zufall).text());
    }

    @Wenn("die Webseite den warmen Schlüssel erneut an der Probe verwendet")
    public void warmenSchluesselVerwenden() {
        antwort = probe(webseitenToken);
    }

    @Dann("enthält die Antwort weder «{word}» noch einen Klassennamen noch «meldung»")
    public void ohneInterna(String eingabe) {
        String text = antwort.asString();
        assertThat(text).doesNotContain("meldung", "Exception", "ch.urinet", "java.", "\tat ");
        if (!"-".equals(eingabe)) {
            assertThat(text).doesNotContain(eingabe);
        }
    }

    @Dann("trägt die Antwort «Cache-Control: no-store»")
    public void keinCache() {
        assertThat(antwort.headers().getValues("Cache-Control")).containsExactly("no-store");
    }

    @Dann("fehlen die Header «Deprecation», «Sunset» und «Link»")
    public void ohneAbkuendigung() {
        assertThat(antwort.headers().hasHeaderWithName("Deprecation")).isFalse();
        assertThat(antwort.headers().hasHeaderWithName("Sunset")).isFalse();
        assertThat(antwort.headers().hasHeaderWithName("Link")).isFalse();
    }

    @After
    public void aufraeumen() throws SQLException, IOException {
        hashSperreFreigeben();
        Files.deleteIfExists(absenderDatei);
        for (String id : tokenIds) {
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement befehl = verbindung.prepareStatement("delete from public.api_client where id = ?")) {
                befehl.setString(1, id);
                befehl.executeUpdate();
            }
        }
    }

    private String erstellen(String webseite, String stufe, boolean absenderPruefen) {
        String text = webseitenVerwaltung.erstellen("urinet", webseite, "lesen", stufe, absenderPruefen)
                .token().text();
        tokenIds.add(ApiToken.lesen(text).orElseThrow().id());
        return text;
    }

    private String anlegen(String name, String art) {
        String text = webseitenVerwaltung.erstellen("urinet", "probe", art,
                "lesen".equals(art) ? "oeffentlich" : null, false).token().text();
        tokenIds.add(ApiToken.lesen(text).orElseThrow().id());
        return text;
    }

    private void rotieren(Duration ueberlappung) {
        alterSchluessel = webseitenToken;
        String id = ApiToken.lesen(alterSchluessel).orElseThrow().id();
        WebseitenSchluesselVerwaltung.Erstellt rotiert = webseitenVerwaltung.rotieren(id, ueberlappung);
        neuerSchluessel = rotiert.token().text();
        tokenIds.add(rotiert.client().id());
    }

    private static Response probe(String token) {
        return given().header("Authorization", "Bearer " + token).when().get("/public/v1/probe");
    }

    private void hashSperreFreigeben() {
        if (hashFreigabe != null) {
            hashFreigabe.countDown();
            hashBesetzer.forEach(CompletableFuture::join);
            hashFreigabe = null;
        }
        if (hashExecutor != null) {
            hashExecutor.shutdownNow();
            hashExecutor = null;
            hashBesetzer = List.of();
        }
    }

    private static void warten(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private RequestSpecification anmelden(String benutzer) {
        if ("niemand".equals(benutzer)) {
            return given();
        }
        if ("webseite".equals(benutzer)) {
            if (webseitenToken == null) {
                webseitenToken = anlegen("anfrage", "lesen");
            }
            return given().header("Authorization", "Bearer " + webseitenToken);
        }
        if ("webseite-erfassung".equals(benutzer)) {
            if (erfassungsToken == null) {
                erfassungsToken = anlegen("erfassung", "erfassung");
            }
            return given().header("Authorization", "Bearer " + erfassungsToken);
        }
        return given().auth().oauth2(Testanmeldung.token(benutzer));
    }

    private static Map<String, Object> ohneInstance(Response antwort) {
        Map<String, Object> inhalt = new TreeMap<>(antwort.jsonPath().getMap(""));
        inhalt.remove("instance");
        return inhalt;
    }

    /** Alle Header mit Werten, Namen klein geschrieben; die Länge hängt nur am gleich langen {@code instance}. */
    private static Map<String, List<String>> kopfzeilen(Response antwort) {
        Map<String, List<String>> kopf = new TreeMap<>();
        for (Header h : antwort.headers()) {
            kopf.computeIfAbsent(h.getName().toLowerCase(java.util.Locale.ROOT), n -> new ArrayList<>()).add(h.getValue());
        }
        kopf.values().forEach(werte -> werte.sort(null));
        return kopf;
    }
}
