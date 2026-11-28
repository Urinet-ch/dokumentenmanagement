package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class FunktionenSchritte {

    private Response antwort;

    @Wenn("«{word}» fragt die Funktionen des Mandanten ab")
    public void funktionenAbfragen(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer))
                .when().get("/api/v1/mandant/funktionen");
    }

    @Wenn("«{word}» fragt die Jahresexporte ab")
    public void jahresexporteAbfragen(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer))
                .when().get("/api/v1/exporte");
    }

    @Dann("sind die Finanzfunktionen verfügbar")
    public void finanzVerfuegbar() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().getBoolean("finanzVerfuegbar")).isTrue();
    }

    @Dann("sind die Finanzfunktionen nicht verfügbar")
    public void finanzNichtVerfuegbar() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().getBoolean("finanzVerfuegbar")).isFalse();
    }

    @Dann("ist Wissen in den Funktionen verfügbar")
    public void wissenVerfuegbar() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().getBoolean("wissenVerfuegbar")).isTrue();
    }

    @Dann("ist Wissen in den Funktionen nicht verfügbar")
    public void wissenNichtVerfuegbar() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().getBoolean("wissenVerfuegbar")).isFalse();
    }

    @Dann("antwortet der Jahresexport mit Status {int} und Fehlercode «{word}»")
    public void exportAntwort(int status, String fehlercode) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(fehlercode);
    }
}
