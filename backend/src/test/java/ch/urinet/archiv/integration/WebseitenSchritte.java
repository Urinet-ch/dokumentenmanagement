package ch.urinet.archiv.integration;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
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

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zur Webseiten-Schnittstelle unter {@code /public/} (Spec 03, Aufgabe 11.6): Anmeldung, Problem Details und
 * Header gegen die Probe {@code /public/v1/probe} ({@code ch.urinet.archiv.probe.ProbeWebseiteResource}). Die Tokens
 * entstehen zur Laufzeit (Scope {@code generator}, bis 11.7 die Webseiten-Scopes bringt) und werden danach gelöscht.
 */
public class WebseitenSchritte {

    @Inject
    ApiTokenVerwaltung verwaltung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final SecureRandom zufall = new SecureRandom();
    private final List<String> tokenIds = new ArrayList<>();
    private final List<Response> abgelehnt = new ArrayList<>();
    private String aktiv;
    private String widerrufen;
    private Response antwort;

    @Angenommen("für «urinet» gibt es ein aktives und ein widerrufenes Token")
    public void tokens() {
        aktiv = anlegen("aktiv");
        widerrufen = anlegen("widerrufen");
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
        antwort = anmelden(benutzer).when().request(methode, pfad);
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
    public void aufraeumen() throws SQLException {
        for (String id : tokenIds) {
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement befehl = verbindung.prepareStatement("delete from public.api_client where id = ?")) {
                befehl.setString(1, id);
                befehl.executeUpdate();
            }
        }
    }

    private String anlegen(String name) {
        String text = verwaltung.erstellen("urinet", "Probe Webseite " + name, "generator").token().text();
        tokenIds.add(ApiToken.lesen(text).orElseThrow().id());
        return text;
    }

    private static RequestSpecification anmelden(String benutzer) {
        return "niemand".equals(benutzer) ? given() : given().auth().oauth2(Testanmeldung.token(benutzer));
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
