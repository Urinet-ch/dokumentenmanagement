package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class KonfigurationsVersionSchritte {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Schema KONFIGURATION_SCHEMA = schema();
    private static final String DOKUMENTARTEN = "/api/v1/dokumentarten";
    private static final String VERSIONEN = "/api/v1/konfiguration/versionen";
    private static final String KONFIGURATION = "/api/v1/konfiguration";
    private static final String KI_TEXTE = KONFIGURATION + "/ki-texte";
    private final List<String> eigeneArten = new ArrayList<>();

    private Response antwort;
    private int versionVorher;
    private int letzteVersion;
    private String konfigurationsdatei;
    private String konfigurationsEtag;
    private String kiRolleVorher;
    private String kiFeldregelnVorher;
    private boolean kiTexteWiederherstellen;

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

    @Wenn("«{word}» die Konfigurationsdatei exportiert")
    public void konfigurationsdateiExportieren(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get(KONFIGURATION);
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        konfigurationsdatei = antwort.asString();
        konfigurationsEtag = antwort.getHeader("ETag");
        versionVorher = Integer.parseInt(konfigurationsEtag.replace("\"", ""));
    }

    @Wenn("«{word}» die exportierte Konfigurationsdatei prüft")
    public void exportierteKonfigurationPruefen(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON)
                .body(konfigurationsdatei).when().post(KONFIGURATION + "/pruefen");
    }

    @Wenn("«{word}» die exportierte Konfigurationsdatei mit Kommentar einspielt")
    public void exportierteKonfigurationMitKommentarEinspielen(String benutzer) {
        String dateiMitKommentar = konfigurationsdatei.replaceFirst("^\\{", "{\"_hinweis\":\"Probe\",");
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", konfigurationsEtag)
                .contentType(ContentType.JSON).body(dateiMitKommentar).when().put(KONFIGURATION);
    }

    @Wenn("«{word}» eine Konfigurationsdatei mit abweichender Basisversion einspielt")
    public void konfigurationMitAbweichenderBasisversionEinspielen(String benutzer) {
        String datei = konfigurationsdatei.replaceFirst("\"basisVersion\"\\s*:\\s*\\d+",
                "\"basisVersion\":" + (versionVorher + 1));
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", konfigurationsEtag)
                .contentType(ContentType.JSON).body(datei).when().put(KONFIGURATION);
    }

    @Wenn("«{word}» eine Konfigurationsdatei mit geänderter KI-Freigabe einspielt")
    public void konfigurationMitGeaenderterKiFreigabeEinspielen(String benutzer) {
        String datei = konfigurationsdatei.replaceFirst("\"kiFreigegeben\"\\s*:\\s*(true|false)",
                "\"kiFreigegeben\":" + !konfigurationsdatei.contains("\"kiFreigegeben\":true"));
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", konfigurationsEtag)
                .contentType(ContentType.JSON).body(datei).when().put(KONFIGURATION);
    }

    @Wenn("«{word}» die Konfigurationsdatei mit ungültiger WISSENSQUELLE einspielt")
    public void ungueltigeWissensquelleEinspielen(String benutzer) throws Exception {
        ObjectNode datei = (ObjectNode) JSON.readTree(konfigurationsdatei);
        ArrayNode arten = (ArrayNode) datei.path("arten");
        for (JsonNode knoten : arten) {
            if (knoten instanceof ObjectNode art && "WISSENSQUELLE".equals(art.path("code").asText())) {
                art.put("kiVorschlag", true);
                break;
            }
        }
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", konfigurationsEtag)
                .contentType(ContentType.JSON).body(JSON.writeValueAsString(datei)).when().put(KONFIGURATION);
    }

    @Wenn("«{word}» die Konfigurationsdatei ohne WISSENSQUELLE einspielt")
    public void konfigurationOhneWissensquelleEinspielen(String benutzer) throws Exception {
        ObjectNode datei = (ObjectNode) JSON.readTree(konfigurationsdatei);
        ArrayNode arten = (ArrayNode) datei.path("arten");
        for (int i = arten.size() - 1; i >= 0; i--) {
            if ("WISSENSQUELLE".equals(arten.get(i).path("code").asText())) {
                arten.remove(i);
            }
        }
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", konfigurationsEtag)
                .contentType(ContentType.JSON).body(JSON.writeValueAsString(datei)).when().put(KONFIGURATION);
    }

    @Wenn("«{word}» die KI-Texte liest")
    public void kiTexteLesen(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get(KI_TEXTE);
        if (antwort.statusCode() == 200) {
            kiRolleVorher = antwort.jsonPath().getString("rolle");
            kiFeldregelnVorher = antwort.jsonPath().getString("feldregeln");
        }
    }

    @Wenn("«{word}» die KI-Texte ohne If-Match ändert")
    public void kiTexteOhneIfMatchAendern(String benutzer) {
        kiTexteWiederherstellen = true;
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON)
                .body("{\"rolle\":\"Synthetische Probe\",\"feldregeln\":\"Probe\",\"grund\":\"Synthetischer Test\"}")
                .when().patch(KI_TEXTE);
    }

    @Wenn("«{word}» die KI-Texte mit der aktuellen Version ändert")
    public void kiTexteMitAktuellerVersionAendern(String benutzer) {
        versionVorher = aktuelleNummer(benutzer);
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", etag(benutzer))
                .contentType(ContentType.JSON)
                .body("{\"rolle\":\"Synthetische Probe\",\"feldregeln\":\"Probe\",\"grund\":\"Synthetischer Test\"}")
                .when().patch(KI_TEXTE);
        etagLesen();
    }

    @Wenn("«{word}» die KI-Texte mit einer veralteten Version ändert")
    public void kiTexteMitVeralteterVersionAendern(String benutzer) {
        versionVorher = aktuelleNummer(benutzer);
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", "\"0\"")
                .contentType(ContentType.JSON)
                .body("{\"rolle\":\"Synthetische Probe\",\"feldregeln\":\"Probe\",\"grund\":\"Synthetischer Test\"}")
                .when().patch(KI_TEXTE);
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

    @Dann("enthält der Export die aktuelle Basisversion und alle Konfigurationsteile")
    public void exportVollstaendig() {
        Map<String, Object> datei = antwort.jsonPath().getMap("$");
        assertThat(datei).containsKeys("format", "basisVersion", "einstellungen", "kiTexte", "klassifikationen",
                "arten", "regeln");
        assertThat(datei.get("format")).isEqualTo(1);
        assertThat(datei.get("basisVersion")).isEqualTo(versionVorher);
        assertThat(konfigurationsEtag).isEqualTo("\"" + versionVorher + "\"");
        List<String> fehler = KONFIGURATION_SCHEMA.validate(konfigurationsdatei, InputFormat.JSON).stream()
                .map(fehlerMessage -> fehlerMessage.getInstanceLocation() + " (" + fehlerMessage.getKeyword() + ")")
                .toList();
        assertThat(fehler).isEmpty();
    }

    @Dann("ist die Konfigurationsdatei gültig und ohne Änderungen")
    public void exportGueltigOhneAenderungen() {
        assertThat(antwort.jsonPath().getBoolean("gueltig")).isTrue();
        assertThat(antwort.jsonPath().<List<Object>>getList("aenderungen")).isEmpty();
    }

    @Dann("meldet das Einspielen keine Änderung")
    public void einspielenOhneAenderung() {
        assertThat(antwort.jsonPath().getBoolean("geaendert")).isFalse();
    }

    @Dann("enthält der KI-Texte-Endpunkt Rolle, Feldregeln und Freigabestatus")
    public void kiTexteVollstaendig() {
        assertThat(antwort.jsonPath().getMap("$")).containsKeys("rolle", "feldregeln", "kiFreigegeben");
    }

    @Dann("enthält der Export die Art «{word}»")
    public void exportEnthaeltArt(String code) {
        List<Map<String, Object>> arten = antwort.jsonPath().getList("arten");
        assertThat(arten).anySatisfy(art -> assertThat(art.get("code")).isEqualTo(code));
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
        if (kiTexteWiederherstellen && kiRolleVorher != null && kiFeldregelnVorher != null) {
            Response stand = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get(KI_TEXTE);
            Response wiederherstellen = given().auth().oauth2(Testanmeldung.token(benutzer))
                    .header("If-Match", stand.getHeader("ETag")).contentType(ContentType.JSON)
                    .body(JSON.createObjectNode().put("rolle", kiRolleVorher).put("feldregeln", kiFeldregelnVorher)
                            .put("grund", "Testwerte wiederherstellen").toString())
                    .when().patch(KI_TEXTE);
            assertThat(wiederherstellen.statusCode()).as(wiederherstellen.asString()).isEqualTo(200);
        }
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

    private static Schema schema() {
        try (InputStream in = KonfigurationsVersionSchritte.class.getResourceAsStream(
                "/konfiguration/konfiguration.schema.json")) {
            if (in == null) {
                throw new IllegalStateException("Schema der Konfigurationsdatei fehlt");
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(new String(in.readAllBytes(), StandardCharsets.UTF_8), InputFormat.JSON);
        } catch (IOException e) {
            throw new IllegalStateException("Schema der Konfigurationsdatei nicht lesbar", e);
        }
    }
}
