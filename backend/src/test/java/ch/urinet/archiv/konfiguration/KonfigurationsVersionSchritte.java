package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class KonfigurationsVersionSchritte {

    private static final String DOKUMENTARTEN = "/api/v1/dokumentarten";
    private static final String VERSIONEN = "/api/v1/konfiguration/versionen";
    private final List<String> eigeneArten = new ArrayList<>();

    private Response antwort;
    private int versionVorher;
    private int letzteVersion;

    @Wenn("«{word}» die Konfigurationsversionen auflistet")
    public void versionenAuflisten(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get(VERSIONEN);
    }

    @Wenn("«{word}» die neueste Konfigurationsversion abruft")
    public void neuesteVersion(String benutzer) {
        int nummer = antwort.jsonPath().<Integer>getList("version").stream().mapToInt(Integer::intValue).max()
                .orElseThrow();
        versionAbrufen(benutzer, nummer);
    }

    @Wenn("«{word}» die aktuelle Konfigurationsversion abruft")
    public void aktuelleVersion(String benutzer) {
        versionenAuflisten(benutzer);
        neuesteVersion(benutzer);
    }

    @Wenn("«{word}» die Version {int} der Konfiguration abruft")
    public void versionAbrufen(String benutzer, int nummer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get(VERSIONEN + "/" + nummer);
        if (antwort.statusCode() == 200) {
            letzteVersion = nummer;
        }
    }

    @Wenn("«{word}» eine Dokumentart ohne If-Match anlegt:")
    public void ohneIfMatchAnlegen(String benutzer, String body) {
        artMerken(body);
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON).body(body)
                .when().post(DOKUMENTARTEN);
    }

    @Wenn("«{word}» eine Dokumentart mit einer veralteten Version anlegt:")
    public void veralteteVersionAnlegen(String benutzer, String body) {
        artMerken(body);
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", "\"0\"")
                .contentType(ContentType.JSON).body(body).when().post(DOKUMENTARTEN);
    }

    @Wenn("«{word}» eine Dokumentart mit der aktuellen Version anlegt:")
    public void aktuelleVersionAnlegen(String benutzer, String body) {
        artMerken(body);
        versionVorher = aktuelleNummer(benutzer);
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", etag(benutzer))
                .contentType(ContentType.JSON).body(body).when().post(DOKUMENTARTEN);
        etagLesen();
    }

    @Wenn("«{word}» die Dokumentart «{word}» mit der aktuellen Version ändert:")
    public void aktuelleVersionAendern(String benutzer, String code, String body) {
        versionVorher = aktuelleNummer(benutzer);
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", etag(benutzer))
                .contentType(ContentType.JSON).body(body).when().patch(DOKUMENTARTEN + "/" + code);
        etagLesen();
    }

    @Wenn("«{word}» die Dokumentart «{word}» ohne If-Match löscht")
    public void ohneIfMatchLoeschen(String benutzer, String code) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().delete(DOKUMENTARTEN + "/" + code);
    }

    @Wenn("«{word}» die Dokumentart «{word}» mit einer veralteten Version löscht")
    public void veralteteVersionLoeschen(String benutzer, String code) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", "\"0\"").when()
                .delete(DOKUMENTARTEN + "/" + code);
    }

    @Wenn("«{word}» die Dokumentart «{word}» mit der aktuellen Version löscht")
    public void aktuelleVersionLoeschen(String benutzer, String code) {
        versionVorher = aktuelleNummer(benutzer);
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", etag(benutzer)).when()
                .delete(DOKUMENTARTEN + "/" + code);
        etagLesen();
    }

    @Wenn("«{word}» die zuvor aktuelle Konfigurationsversion abruft")
    public void vorherigeVersion(String benutzer) {
        versionAbrufen(benutzer, versionVorher);
    }

    @Dann("antwortet die Konfigurationsverwaltung mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("antwortet die Konfigurationsverwaltung mit Status {int} und Fehlercode «{word}»")
    public void fehlercode(int status, String code) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
        assertThat(antwort.jsonPath().getString("fehlercode")).isEqualTo(code);
    }

    @Dann("enthält die Antwort mindestens eine Konfigurationsversion")
    public void mindestensEineVersion() {
        assertThat(antwort.jsonPath().<List<Integer>>getList("version")).isNotEmpty();
    }

    @Dann("enthält die Antwort ein vollständiges Konfigurationsabbild")
    public void vollstaendigesAbbild() {
        Map<String, Object> inhalt = antwort.jsonPath().getMap("inhalt");
        assertThat(inhalt).containsKeys("version", "einstellungen", "arten");
    }

    @Dann("enthält die aktuelle Konfiguration die Art «{word}»")
    public void aktuelleKonfigurationEnthaelt(String code) {
        List<Map<String, Object>> arten = antwort.jsonPath().getList("inhalt.arten");
        assertThat(arten.stream().anyMatch(art -> code.equals(art.get("code")))).isTrue();
    }

    @Dann("enthält die aktuelle Konfiguration die Art «{word}» nicht")
    public void aktuelleKonfigurationEnthaeltNicht(String code) {
        List<Map<String, Object>> arten = antwort.jsonPath().getList("inhalt.arten");
        assertThat(arten.stream().noneMatch(art -> code.equals(art.get("code")))).isTrue();
    }

    @Dann("ist die Konfigurationsversion um eins gestiegen")
    public void versionGestiegen() {
        assertThat(letzteVersion).isEqualTo(versionVorher + 1);
    }

    @Dann("ist die Konfigurationsversion unverändert")
    public void versionUnveraendert() {
        assertThat(aktuelleNummer("inhaber-urinet")).isEqualTo(versionVorher);
    }

    @Dann("ist die Art «{word}» in dieser Version unter dem Namen {string} enthalten")
    public void historischeArt(String code, String name) {
        List<Map<String, Object>> arten = antwort.jsonPath().getList("inhalt.arten");
        assertThat(arten).anySatisfy(art -> {
            assertThat(art.get("code")).isEqualTo(code);
            assertThat(art.get("name")).isEqualTo(name);
        });
    }

    @After
    public void aufraeumen() {
        String benutzer = "inhaber-urinet";
        for (String code : eigeneArten) {
            String aktuelleEtag = etag(benutzer);
            Response loeschen = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", aktuelleEtag)
                    .when().delete(DOKUMENTARTEN + "/" + code);
            assertThat(loeschen.statusCode()).as(loeschen.asString()).isIn(204, 404);
        }
        eigeneArten.clear();
    }

    private int aktuelleNummer(String benutzer) {
        Response liste = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get(VERSIONEN);
        assertThat(liste.statusCode()).as(liste.asString()).isEqualTo(200);
        return liste.jsonPath().<Integer>getList("version").stream().mapToInt(Integer::intValue).max().orElseThrow();
    }

    private String etag(String benutzer) {
        Response stand = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get(DOKUMENTARTEN);
        assertThat(stand.statusCode()).as(stand.asString()).isEqualTo(200);
        return stand.getHeader("ETag");
    }

    private void artMerken(String body) {
        String code = io.restassured.path.json.JsonPath.from(body).getString("code");
        if (code != null && !eigeneArten.contains(code)) {
            eigeneArten.add(code);
        }
    }

    private void etagLesen() {
        if (antwort.getHeader("ETag") != null) {
            letzteVersion = Integer.parseInt(antwort.getHeader("ETag").replace("\"", ""));
        }
    }
}
