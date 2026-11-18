package ch.urinet.archiv.wissen;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class WissenIngestSchritte {

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final Map<String, UUID> dokumente = new HashMap<>();
    private Response antwort;

    @After
    public void aufraeumen() throws SQLException {
        Testdokumente.loeschen(datenbank.get(), "urinet", dokumente.values());
        dokumente.clear();
    }

    @Angenommen("im Mandanten «urinet» liegt ein archiviertes Dokument «{word}» der Art «{word}»")
    public void dokumentAnlegen(String name, String art) throws SQLException {
        UUID id = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben("urinet", art, "NORMAL", true, "probe", null, "ARCHIVIERT"));
        dokumente.put(name, id);
    }

    @Wenn("«{word}» den Ingest für «{word}» startet")
    public void ingestStarten(String benutzer, String name) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON)
                .body(Map.of()).when().post("/api/v1/dokumente/" + dokumente.get(name) + "/wissen-ingest");
    }

    @Dann("antwortet der Ingest-Endpunkt mit Status {int} und Fehlercode «{word}»")
    public void fehlercode(int status, String code) {
        assertThat(ProblemDetails.pruefen(antwort, status, "felder")).as(antwort.asString()).isEqualTo(code);
    }

    @Dann("antwortet der Ingest-Endpunkt mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }
}
