package ch.urinet.archiv.mandant;

import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class BrandingSchritte {

    private Response antwort;
    private String standardAntwort;
    private java.util.Set<?> bekannteFelder;
    private String bekannterCacheHeader;

    @Wenn("das öffentliche Branding für die Domain {string} angefragt wird")
    public void brandingAnfragen(String domain) {
        antwort = given().queryParam("domain", domain).get("/api/v1/branding");
    }

    @Wenn("das öffentliche Branding ohne Domain angefragt wird")
    public void brandingOhneDomainAnfragen() {
        antwort = given().get("/api/v1/branding");
    }

    @Wenn("das öffentliche Branding für eine Domain mit 254 Zeichen angefragt wird")
    public void brandingMitUeberlangerDomainAnfragen() {
        antwort = given().queryParam("domain", "a".repeat(254)).get("/api/v1/branding");
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
        assertThat(antwort.header("Cache-Control")).contains("max-age=60");
        standardAntwort = antwort.asString();
    }

    @Dann("antwortet das Archiv mit derselben neutralen Antwort")
    public void neutraleAntwortVergleichen() {
        assertThat(antwort.asString()).isEqualTo(standardAntwort);
        assertThat(antwort.asString()).doesNotContain("urinet", "vhf-gsk");
    }

    @Wenn("Daten mit der Domain {string} ohne Token angefragt werden")
    public void datenOhneTokenAnfragen(String domain) {
        antwort = given().queryParam("domain", domain).get("/api/v1/dokumente");
    }

    @Dann("liefert das Archiv das neutrale Branding ohne Logo")
    public void neutralesBrandingPruefen() {
        assertThat(antwort.jsonPath().getString("name")).isEqualTo("Archiv");
        assertThat(antwort.jsonPath().getString("primaerfarbe")).isEqualTo("#eeb111");
        assertThat(antwort.jsonPath().getString("logoUrl")).isNull();
    }

    @Dann("die Antwort enthält weder {string} noch {string} noch {string}")
    public void antwortEnthaeltNicht(String a, String b, String c) {
        assertThat(antwort.asString()).doesNotContain(a, b, c);
    }

    @Dann("die Feldmenge der Antwort ist gespeichert")
    public void feldmengeSpeichern() {
        bekannteFelder = antwort.jsonPath().getMap("$").keySet();
        bekannterCacheHeader = antwort.header("Cache-Control");
    }

    @Dann("hat die Antwort dieselbe Feldmenge und denselben Cache-Control-Header wie die bekannte Domain")
    public void feldmengeVergleichen() {
        assertThat(antwort.jsonPath().getMap("$").keySet()).isEqualTo(bekannteFelder);
        assertThat(antwort.header("Cache-Control")).isEqualTo(bekannterCacheHeader);
    }
}
