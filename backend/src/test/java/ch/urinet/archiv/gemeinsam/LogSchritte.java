package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.security.AuthenticationFailedException;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Req 16.7, Vorab-Korrektur 3 aus dem Review von PR #35: Fehler der Datenbank und abgelehnte Tokens hinterlassen keine
 * Werte im Log. Geprüft wird das ganze Log samt Stacktraces.
 */
public class LogSchritte {

    @Inject
    Instance<AgroalDataSource> datenbank;

    private LogMitschnitt mitschnitt;
    private final List<String> werte = new ArrayList<>();
    private final List<String[]> angelegteArten = new ArrayList<>();
    private final List<Response> antworten = new ArrayList<>();

    @Angenommen("das ganze Log wird mitgeschnitten")
    public void mitschneiden() {
        mitschnitt = LogMitschnitt.starten();
    }

    @Wenn("«{word}» zweimal die Dokumentart «{word}» über das Probe-Dokument «{word}» anlegt")
    public void zweimalAnlegen(String benutzer, String code, String dokument) {
        angelegteArten.add(new String[]{new MandantCode("urinet").schema(), code});
        werte.add(code);
        for (int i = 0; i < 2; i++) {
            antworten.add(given().auth().oauth2(Testanmeldung.token(benutzer)).queryParam("art", code).when()
                    .patch("/api/v1/probe/dokumente/" + Testdokumente.NAMEN.get(dokument)));
        }
    }

    @Wenn("«{word}» mit verfälschter Signatur die Dokumentart «{word}» abfragt")
    public void verfaelscht(String benutzer, String code) throws IOException {
        String token = Testanmeldung.token(benutzer);
        String[] teile = token.split("\\.");
        char[] signatur = teile[2].toCharArray();
        signatur[10] = signatur[10] == 'A' ? 'B' : 'A';
        String falsch = teile[0] + "." + teile[1] + "." + new String(signatur);
        JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(teile[1]));
        werte.add(falsch);
        werte.add(teile[1]);
        werte.add(new String(signatur));
        werte.add(claims.path("email").asText());
        werte.add(claims.path("preferred_username").asText());
        werte.add(claims.path("name").asText());
        antworten.add(given().auth().oauth2(falsch).when().get("/api/v1/probe/dokumentarten/" + code));
    }

    @Dann("scheitert die zweite Anlage mit Status {int} und Fehlercode «{word}»")
    public void zweiteScheitert(int status, String fehlercode) {
        assertThat(antworten.get(0).statusCode()).isEqualTo(204);
        assertThat(ProblemDetails.pruefen(antworten.get(1), status)).isEqualTo(fehlercode);
    }

    @Dann("wird das verfälschte Token mit Status {int} und Fehlercode «{word}» abgelehnt")
    public void abgelehnt(int status, String fehlercode) {
        assertThat(ProblemDetails.pruefen(antworten.getLast(), status)).isEqualTo(fehlercode);
    }

    @Dann("enthält weder eine Antwort noch das ganze Log einen dieser Werte")
    public void ohneWerte() {
        String log = mitschnitt.volltext();
        assertThat(mitschnitt.eintraege()).as("Log mitgeschnitten").isNotEmpty();
        for (String wert : werte) {
            assertThat(wert).isNotBlank();
            assertThat(log).as("Log").doesNotContain(wert);
            for (Response antwort : antworten) {
                assertThat(antwort.asString()).as("Antwort").doesNotContain(wert);
            }
        }
    }

    @Dann("nennt das Log zur zweiten Anlage die Ursachen-Klassen mit SQLState «{word}»")
    public void ursacheDerAnlage(String sqlState) {
        assertThat(ursache(antworten.get(1))).contains("[sqlstate=" + sqlState + "]");
    }

    @Dann("nennt das Log zum verfälschten Token die Ursachen-Klassen")
    public void ursacheDesTokens() {
        assertThat(ursache(antworten.getLast())).startsWith(AuthenticationFailedException.class.getName());
    }

    /** Teil {@code ursache=…} des Eintrags zur Fehler-ID der Antwort. */
    private String ursache(Response antwort) {
        String fehlerId = antwort.jsonPath().getString("instance").substring("urn:uuid:".length());
        List<String> zeilen = mitschnitt.eintraege().stream().map(mitschnitt::nachricht)
                .filter(zeile -> zeile.startsWith("Fehler " + fehlerId + ":")).toList();
        assertThat(zeilen).as("Logeintrag zur Fehler-ID").singleElement().asString().contains(" ursache=");
        return zeilen.getFirst().substring(zeilen.getFirst().indexOf(" ursache=") + " ursache=".length());
    }

    @After
    public void aufraeumen() throws SQLException {
        if (mitschnitt != null) {
            mitschnitt.close();
        }
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String[] art : angelegteArten) {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from " + art[0] + ".dokumentart where code = ?")) {
                    loeschen.setString(1, art[1]);
                    loeschen.executeUpdate();
                }
            }
        }
    }
}
