package ch.urinet.archiv.mandant;

import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class BrandingSchritte {

    private Response antwort;
    private String standardAntwort;

    @Wenn("das öffentliche Branding für die Domain {string} angefragt wird")
    public void brandingAnfragen(String domain) {
        antwort = given().queryParam("domain", domain).get("/api/v1/branding");
    }

    @Dann("antwortet das Archiv mit Status {int}")
    public void statusPruefen(int status) {
        assertThat(antwort.statusCode()).isEqualTo(status);
    }

    @Dann("liefert das Archiv das Branding {string} mit Farbe {string}")
    public void brandingPruefen(String name, String farbe) {
        assertThat(antwort.jsonPath().getString("name")).isEqualTo(name);
        assertThat(antwort.jsonPath().getString("primaerfarbe")).isEqualTo(farbe);
        assertThat(antwort.jsonPath().getString("sprache")).isEqualTo("de-CH");
        assertThat(antwort.jsonPath().getString("logoUrl")).isNull();
        assertThat(antwort.header("Cache-Control")).contains("max-age=60");
    }

    @Dann("die neutrale Antwort ist gespeichert")
    public void neutraleAntwortSpeichern() {
        assertThat(antwort.jsonPath().getString("name")).isEqualTo("Archiv");
        standardAntwort = antwort.asString();
    }

    @Dann("antwortet das Archiv mit derselben neutralen Antwort")
    public void neutraleAntwortVergleichen() {
        assertThat(antwort.asString()).isEqualTo(standardAntwort);
        assertThat(antwort.asString()).doesNotContain("urinet", "vhf-gsk");
    }
}
