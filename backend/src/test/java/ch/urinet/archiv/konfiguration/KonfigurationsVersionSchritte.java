package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.benutzer.application.VerwaltungGrenzen;
import ch.urinet.archiv.benutzer.adapter.out.persistence.JdbcVerwaltungGrenzen;
import ch.urinet.archiv.benutzer.adapter.out.stepup.StepUpMitAuthTime;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final List<UUID> eigeneDokumente = new ArrayList<>();

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject StepUp stepUp;
    private StepUp urspruenglichesStepUp;
    @Inject VerwaltungGrenzen grenzen;
    private VerwaltungGrenzen urspruenglicheGrenzen;
    private Response antwort;
    private int versionVorher;
    private int letzteVersion;
    private String konfigurationsdatei;
    private String konfigurationsEtag;
    private String kiRolleVorher;
    private String kiFeldregelnVorher;
    private boolean kiTexteWiederherstellen;
    private ObjectNode datei;
    private ObjectNode ausgangsdatei;
    private boolean dateiWiederherstellen;
    private final Map<String, Integer> gemerkteVersionen = new java.util.HashMap<>();

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
        try {
            datei = (ObjectNode) JSON.readTree(konfigurationsdatei);
        } catch (IOException e) {
            throw new IllegalStateException("Export nicht lesbar", e);
        }
        if (ausgangsdatei == null && "inhaber-urinet".equals(benutzer)) {
            ausgangsdatei = datei.deepCopy();
        }
    }

    @Wenn("«{word}» die exportierte Konfigurationsdatei prüft")
    public void exportierteKonfigurationPruefen(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON)
                .body(konfigurationsdatei).when().post(KONFIGURATION + "/pruefen");
    }

    @Wenn("«{word}» die exportierte Konfigurationsdatei mit Kommentar einspielt")
    public void exportierteKonfigurationMitKommentarEinspielen(String benutzer) {
        String dateiMitKommentar = konfigurationsdatei.replaceFirst("^\\{", "{\"_hinweis\":\"Probe\",");
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer))
                .contentType(ContentType.JSON).body(dateiMitKommentar).when().put(KONFIGURATION);
    }

    @Wenn("«{word}» eine Konfigurationsdatei mit abweichender Basisversion einspielt")
    public void konfigurationMitAbweichenderBasisversionEinspielen(String benutzer) {
        String datei = konfigurationsdatei.replaceFirst("\"basisVersion\"\\s*:\\s*\\d+",
                "\"basisVersion\":" + (versionVorher + 1));
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer))
                .contentType(ContentType.JSON).body(datei).when().put(KONFIGURATION);
    }

    @Wenn("«{word}» eine Konfigurationsdatei mit geänderter KI-Freigabe einspielt")
    public void konfigurationMitGeaenderterKiFreigabeEinspielen(String benutzer) {
        String datei = konfigurationsdatei.replaceFirst("\"kiFreigegeben\"\\s*:\\s*(true|false)",
                "\"kiFreigegeben\":" + !konfigurationsdatei.contains("\"kiFreigegeben\":true"));
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer))
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
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer))
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
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer))
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
        kiTexteWiederherstellen = true;
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

    @Angenommen("«{word}» merkt sich die Konfigurationsversion")
    public void versionMerken(String benutzer) {
        gemerkteVersionen.put(benutzer, aktuelleNummer(benutzer));
    }

    @Wenn("in der Datei die Art «{word}» ergänzt wird")
    public void artErgaenzen(String code) {
        ObjectNode art = JSON.createObjectNode();
        art.put("code", code).put("name", "Test " + code);
        art.putArray("gruppen").add("frei");
        art.putArray("pflichtfelder").add("titel");
        art.put("vertraulichkeit", "NORMAL");
        art.putObject("aufbewahrung").put("jahre", 10).put("ab", "BELEGDATUM");
        art.put("gesetzlichePflicht", false).put("aktiv", true).put("selbstAusgestellt", false);
        art.putObject("zusatzSchema").put("type", "object").put("additionalProperties", false)
                .putObject("properties");
        art.putArray("klassifikationen");
        ((ArrayNode) datei.path("arten")).add(art);
        if (!eigeneArten.contains(code)) {
            eigeneArten.add(code);
        }
    }

    @Wenn("in der Datei die Art «{word}» als Fremdbeleg auf «{word}» verweist")
    public void artVerweist(String code, String ziel) {
        art(code).put("fremdbelegArt", ziel);
    }

    @Wenn("in der Datei die Art «{word}» fehlt")
    public void artEntfernen(String code) {
        ArrayNode arten = (ArrayNode) datei.path("arten");
        for (int i = arten.size() - 1; i >= 0; i--) {
            if (code.equals(arten.get(i).path("code").asText())) {
                arten.remove(i);
            }
        }
    }

    @Wenn("in der Datei die Aufbewahrung der Art «{word}» {int} Jahre beträgt")
    public void aufbewahrungSetzen(String code, int jahre) {
        ((ObjectNode) art(code).path("aufbewahrung")).put("jahre", jahre);
    }

    @Wenn("in der Datei die mehrfache Klassifikation «{word}» mit den Werten «{word}» und «{word}» ergänzt wird")
    public void klassifikationErgaenzen(String code, String wert1, String wert2) {
        ObjectNode klasse = JSON.createObjectNode();
        klasse.put("code", code).put("name", "Test " + code);
        ArrayNode werte = klasse.putArray("werte");
        werte.addObject().put("code", wert1).put("name", wert1);
        werte.addObject().put("code", wert2).put("name", wert2);
        klasse.put("mehrfach", true).put("kiVorschlag", false).put("suchfilter", false)
                .put("oeffentlichFilter", false).put("aktiv", true);
        ((ArrayNode) datei.path("klassifikationen")).add(klasse);
    }

    /** 2 × 8'000 Zeichen KI-Texte plus 9 Werte mit je 1'000 Zeichen Beschreibung: über 24'000 (design.md, Req 6.6). */
    @Wenn("in der Datei die KI-Texte und die Klassifikation «{word}» mit KI-Vorschlag zusammen über 24'000 Zeichen lang sind")
    public void promptZuLang(String code) {
        ObjectNode texte = (ObjectNode) datei.path("kiTexte");
        texte.put("rolle", "r".repeat(8000)).put("feldregeln", "f".repeat(8000));
        ObjectNode klasse = JSON.createObjectNode();
        klasse.put("code", code).put("name", "Test " + code);
        ArrayNode werte = klasse.putArray("werte");
        for (int i = 0; i < 9; i++) {
            werte.addObject().put("code", "w" + i).put("name", "W" + i).put("beschreibung", "b".repeat(1000));
        }
        klasse.put("mehrfach", false).put("kiVorschlag", true).put("suchfilter", false)
                .put("oeffentlichFilter", false).put("aktiv", true);
        ((ArrayNode) datei.path("klassifikationen")).add(klasse);
    }

    @Wenn("in der Datei die Klassifikation «{word}» einfach ist")
    public void klassifikationEinfach(String code) {
        klasse(code).put("mehrfach", false);
    }

    @Wenn("in der Datei die Klassifikation «{word}» inaktiv ist")
    public void klassifikationInaktiv(String code) {
        klasse(code).put("aktiv", false);
    }

    @Wenn("in der Datei die Klassifikation «{word}» fehlt")
    public void klassifikationEntfernen(String code) {
        ArrayNode klassen = (ArrayNode) datei.path("klassifikationen");
        for (int i = klassen.size() - 1; i >= 0; i--) {
            if (code.equals(klassen.get(i).path("code").asText())) {
                klassen.remove(i);
            }
        }
    }

    @Wenn("in der Datei die Art «{word}» der Klassifikation «{word}» als Pflicht zugeordnet ist")
    public void zuordnen(String art, String klasse) {
        ((ArrayNode) art(art).path("klassifikationen")).addObject().put("code", klasse).put("pflicht", true);
    }

    @Wenn("in der Datei die Art «{word}» der Klassifikation «{word}» mit dem Standardwert «{word}» zugeordnet ist")
    public void mitStandardwertZuordnen(String art, String klasse, String wert) {
        ArrayNode zuordnungen = (ArrayNode) art(art).path("klassifikationen");
        for (int i = zuordnungen.size() - 1; i >= 0; i--) {
            if (klasse.equals(zuordnungen.get(i).path("code").asText())) {
                zuordnungen.remove(i);
            }
        }
        zuordnungen.addObject().put("code", klasse).put("pflicht", false).put("standardWert", wert);
    }

    @Wenn("in der Datei der Wert «{word}» der Klassifikation «{word}» fehlt")
    public void wertEntfernen(String wert, String klasse) {
        ArrayNode werte = (ArrayNode) klasse(klasse).path("werte");
        for (int i = werte.size() - 1; i >= 0; i--) {
            if (wert.equals(werte.get(i).path("code").asText())) {
                werte.remove(i);
            }
        }
    }

    /** Wie ein Abbild vor Changeset 048 oder von einem älteren Release: ohne {@code kiTexte} (Req 6.6, 8.4). */
    @Angenommen("die letzte Konfigurationsversion von «{word}» enthält keine KI-Texte")
    public void versionOhneKiTexte(String mandant) throws SQLException {
        String schema = new MandantCode(mandant).schema();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement anlegen = verbindung.prepareStatement("with letzte as (select version, inhalt from "
                     + schema + ".konfiguration_version order by version desc limit 1), "
                     + "abbild as (select version + 1 as version, (inhalt - 'kiTexte') || jsonb_build_object("
                     + "'version', version + 1) as inhalt from letzte) "
                     + "insert into " + schema + ".konfiguration_version (version, wer, grund, quelle, inhalt, sha256) "
                     + "select version, 'system:migration', null, 'MIGRATION', inhalt, "
                     + "encode(sha256(convert_to(inhalt::text, 'UTF8')), 'hex') from abbild")) {
            assertThat(anlegen.executeUpdate()).isEqualTo(1);
        }
    }

    @Wenn("in der Datei das Feld «{word}» mit dem Wert «{word}» steht")
    public void feldSetzen(String feld, String wert) {
        datei.put(feld, wert);
    }

    @Wenn("in der Datei das Geschäftsjahresende «{word}» steht")
    public void geschaeftsjahrEnde(String datum) {
        ((ObjectNode) datei.path("einstellungen")).put("geschaeftsjahrEnde", datum);
    }

    @Wenn("in der Datei die Basisversion veraltet ist")
    public void basisversionVeraltet() {
        datei.put("basisVersion", versionVorher + 1);
    }

    @Wenn("in der Datei die erweiterten Einstellungen mit Testwerten gesetzt werden")
    public void erweiterteEinstellungenSetzen() {
        ObjectNode einstellungen = (ObjectNode) datei.path("einstellungen");
        ((ObjectNode) einstellungen.path("branding")).put("name", "Testarchiv").put("primaerfarbe", "#eeb111");
        ((ObjectNode) einstellungen.path("chat")).put("aufbewahrungMonate", 17);
        ((ObjectNode) einstellungen.path("webseitenChat")).put("aufbewahrungTage", 31);
    }

    @Wenn("in der Datei die Chat-Aufbewahrung auf {int} Monate gesetzt wird")
    public void chatAufbewahrungSetzen(int monate) {
        ((ObjectNode) datei.path("einstellungen").path("chat")).put("aufbewahrungMonate", monate);
    }

    @Wenn("in der Datei die Einstellung {string} in der Gruppe {string} auf den JSON-Wert {string} gesetzt wird")
    public void einstellungRohSetzen(String schluessel, String gruppe, String jsonWert) throws Exception {
        ObjectNode einstellungen = (ObjectNode) datei.path("einstellungen");
        ObjectNode block = einstellungen.has(gruppe) ? (ObjectNode) einstellungen.get(gruppe) : einstellungen.putObject(gruppe);
        block.set(schluessel, JSON.readTree(jsonWert));
    }

    @Wenn("in der Datei die IBAN-Liste mit {int} Einträgen gesetzt wird")
    public void ibanListeSetzen(int anzahl) {
        var liste = JSON.createArrayNode();
        for (int i = 0; i < anzahl; i++) {
            liste.add("CH00 SYNTH " + i);
        }
        ((ObjectNode) datei.path("einstellungen").path("kennungen")).set("ibans", liste);
    }

    @Dann("enthält der Export die erweiterten Testwerte")
    public void exportEnthaeltErweiterteTestwerte() {
        JsonNode einstellungen = datei.path("einstellungen");
        assertThat(einstellungen.path("branding").path("name").asText()).isEqualTo("Testarchiv");
        assertThat(einstellungen.path("branding").path("primaerfarbe").asText()).isEqualTo("#eeb111");
        assertThat(einstellungen.path("chat").path("aufbewahrungMonate").asInt()).isEqualTo(17);
        assertThat(einstellungen.path("webseitenChat").path("aufbewahrungTage").asInt()).isEqualTo(31);
    }

    @Wenn("in der Datei der KI-Rollentext geändert wird")
    public void kiRollentextAendern() {
        kiTexteWiederherstellen = true;
        ((ObjectNode) datei.path("kiTexte")).put("rolle", "Synthetische Testrolle");
    }

    @Wenn("«{word}» die geänderte Konfigurationsdatei einspielt")
    public void geaenderteDateiEinspielen(String benutzer) throws IOException {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON)
                .body(JSON.writeValueAsString(datei)).when().put(KONFIGURATION);
        if (antwort.statusCode() == 200) {
            dateiWiederherstellen = true;
            etagLesen();
        }
    }

    @Angenommen("für die Konfigurationsdatei ist eine frische Anmeldung bestätigt")
    public void frischeAnmeldung() {
        urspruenglichesStepUp = ClientProxy.unwrap(stepUp);
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
    }

    @Before("@20-1-abbild or @20-2-rest")
    public void grenzenFuerEinstellungsszenarienIsolieren() {
        urspruenglicheGrenzen = ClientProxy.unwrap(grenzen);
        QuarkusMock.installMockForType(new JdbcVerwaltungGrenzen(null, null, 1, 1, 1, 1) {
            @Override
            public void pruefen(String akteur, Art art, UUID ziel) { }
        }, VerwaltungGrenzen.class);
    }

    @Wenn("«{word}» die geänderte Konfigurationsdatei mit Bestätigung {string} einspielt")
    public void geaenderteDateiBestaetigtEinspielen(String benutzer, String bestaetigt) throws IOException {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).queryParam("bestaetigt", bestaetigt)
                .contentType(ContentType.JSON).body(JSON.writeValueAsString(datei)).when().put(KONFIGURATION);
        if (antwort.statusCode() == 200) {
            dateiWiederherstellen = true;
            etagLesen();
        }
    }

    @Wenn("«{word}» die geänderte Konfigurationsdatei prüft")
    public void geaenderteDateiPruefen(String benutzer) throws IOException {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON)
                .body(JSON.writeValueAsString(datei)).when().post(KONFIGURATION + "/pruefen");
    }

    @Wenn("«{word}» die exportierte Konfigurationsdatei ohne If-Match einspielt")
    public void exportierteDateiEinspielen(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON)
                .body(konfigurationsdatei).when().put(KONFIGURATION);
    }

    @Wenn("«{word}» die Konfigurationsverwaltung mit {word} {string} aufruft")
    public void aufrufen(String benutzer, String methode, String pfad) {
        var anfrage = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", "\"1\"");
        if (!"GET".equals(methode)) {
            anfrage = anfrage.contentType(ContentType.JSON).body("{}");
        }
        antwort = anfrage.when().request(methode, KONFIGURATION + pfad);
    }

    @Angenommen("«{word}» legt ein Dokument der Art «{word}» an")
    public void dokumentAnlegen(String benutzer, String art) throws SQLException {
        dokumentMitKlassifikation(benutzer, art, null, List.of());
    }

    @Angenommen("«{word}» legt ein Dokument der Art «{word}» mit den Werten «{word}» und «{word}» der Klassifikation «{word}» an")
    public void dokumentMitWerten(String benutzer, String art, String wert1, String wert2, String klasse)
            throws SQLException {
        dokumentMitKlassifikation(benutzer, art, klasse, List.of(wert1, wert2));
    }

    @Dann("hat die Konfigurationsversion die Quelle «{word}»")
    public void quelle(String quelle) {
        Response version = given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).when()
                .get(VERSIONEN + "/" + letzteVersion);
        assertThat(version.statusCode()).as(version.asString()).isEqualTo(200);
        assertThat(version.jsonPath().getString("version.quelle")).isEqualTo(quelle);
    }

    @Dann("hat der Mandant «{word}» die erste Version aus der Migration und die letzte aus dem Seed")
    public void seedVersion(String code) throws SQLException {
        String schema = new MandantCode(code).schema();
        try (Connection verbindung = datenbank.get().getConnection();
                PreparedStatement abfrage = verbindung.prepareStatement(
                        "select version, quelle from " + schema + ".konfiguration_version order by version");
                var ergebnis = abfrage.executeQuery()) {
            assertThat(ergebnis.next()).as("Migrationsversion vorhanden").isTrue();
            assertThat(ergebnis.getInt(1)).isEqualTo(1);
            assertThat(ergebnis.getString(2)).isEqualTo("MIGRATION");
            int letzteVersion = ergebnis.getInt(1);
            String letzteQuelle = ergebnis.getString(2);
            while (ergebnis.next()) {
                letzteVersion = ergebnis.getInt(1);
                letzteQuelle = ergebnis.getString(2);
            }
            assertThat(letzteVersion).isGreaterThan(1);
            assertThat(letzteQuelle).isEqualTo("SEED");
        }
    }

    @Dann("ist die Konfigurationsversion von «{word}» unverändert")
    public void gemerkteVersionUnveraendert(String benutzer) {
        assertThat(aktuelleNummer(benutzer)).isEqualTo(gemerkteVersionen.get(benutzer));
    }

    @Dann("nennt der letzte Eintrag «{word}» im Protokoll von «{word}» den Text «{word}»")
    public void protokolleintrag(String aktion, String mandant, String text) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select details::text from "
                     + new MandantCode(mandant).schema() + ".zugriff_protokoll where aktion = ? "
                     + "order by id desc limit 1")) {
            abfrage.setString(1, aktion);
            try (var ergebnis = abfrage.executeQuery()) {
                assertThat(ergebnis.next()).as("Protokolleintrag " + aktion).isTrue();
                assertThat(ergebnis.getString(1)).contains(text);
            }
        }
    }

    @Dann("nennt der letzte Eintrag «{word}» im Protokoll von «{word}» den Text «{word}» nicht")
    public void protokolleintragOhne(String aktion, String mandant, String text) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select details::text from "
                     + new MandantCode(mandant).schema() + ".zugriff_protokoll where aktion = ? "
                     + "order by id desc limit 1")) {
            abfrage.setString(1, aktion);
            try (var ergebnis = abfrage.executeQuery()) {
                assertThat(ergebnis.next()).as("Protokolleintrag " + aktion).isTrue();
                assertThat(ergebnis.getString(1)).doesNotContain(text);
            }
        }
    }

    @Dann("ist die KI im Export freigegeben")
    public void exportKiFreigegeben() {
        assertThat(datei.path("kiTexte").path("kiFreigegeben").asBoolean()).as(konfigurationsdatei).isTrue();
    }

    @Dann("ist im Export «{word}» der Standardwert der Klassifikation «{word}» der Art «{word}»")
    public void exportStandardwert(String wert, String klasse, String art) {
        List<Map<String, Object>> zuordnungen = antwort.jsonPath()
                .getList("arten.find { it.code == '" + art + "' }.klassifikationen");
        assertThat(zuordnungen).anySatisfy(zuordnung -> {
            assertThat(zuordnung.get("code")).isEqualTo(klasse);
            assertThat(zuordnung.get("standardWert")).isEqualTo(wert);
        });
    }

    @Dann("enthält der Export die Art «{word}» nicht")
    public void exportOhneArt(String code) {
        List<Map<String, Object>> arten = antwort.jsonPath().getList("arten");
        assertThat(arten).noneSatisfy(art -> assertThat(art.get("code")).isEqualTo(code));
    }

    @Dann("ist im Export die Art «{word}» inaktiv")
    public void exportArtInaktiv(String code) {
        List<Map<String, Object>> arten = antwort.jsonPath().getList("arten");
        assertThat(arten).anySatisfy(art -> {
            assertThat(art.get("code")).isEqualTo(code);
            assertThat(art.get("aktiv")).isEqualTo(false);
        });
    }

    @Dann("ist im Export die Klassifikation «{word}» inaktiv")
    public void exportKlassifikationInaktiv(String code) {
        List<Map<String, Object>> klassen = antwort.jsonPath().getList("klassifikationen");
        assertThat(klassen).anySatisfy(klasse -> {
            assertThat(klasse.get("code")).isEqualTo(code);
            assertThat(klasse.get("aktiv")).isEqualTo(false);
        });
    }

    @Dann("ist im Export die Klassifikation «{word}» der Art «{word}» weder Pflicht noch vorbelegt")
    public void zuordnungEntschaerft(String klasse, String art) {
        List<Map<String, Object>> zuordnungen = antwort.jsonPath()
                .getList("arten.find { it.code == '" + art + "' }.klassifikationen");
        assertThat(zuordnungen).anySatisfy(zuordnung -> {
            assertThat(zuordnung.get("code")).isEqualTo(klasse);
            assertThat(zuordnung.get("pflicht")).isEqualTo(false);
            assertThat(zuordnung.get("standardWert")).isNull();
        });
    }

    @Dann("nennt die Antwort den Befund {string} mit dem Code «{word}»")
    public void befund(String pfad, String code) {
        List<Map<String, Object>> befunde = antwort.jsonPath().getList("befunde");
        assertThat(befunde).as(antwort.asString()).anySatisfy(befund -> {
            assertThat(befund.get("pfad")).isEqualTo(pfad);
            assertThat(befund.get("code")).isEqualTo(code);
        });
    }

    @Dann("nennt das Ergebnis die Änderung {string} mit {int} betroffenen Dokument(en)")
    public void aenderungGenannt(String pfad, int anzahl) {
        List<Map<String, Object>> aenderungen = antwort.jsonPath().getList("aenderungen");
        assertThat(aenderungen).as(antwort.asString()).anySatisfy(aenderung -> {
            assertThat(aenderung.get("pfad")).isEqualTo(pfad);
            assertThat(((Number) aenderung.get("betroffeneDokumente")).intValue()).isEqualTo(anzahl);
        });
    }

    @Dann("ist die Konfigurationsdatei ungültig")
    public void dateiUngueltig() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().getBoolean("gueltig")).isFalse();
    }

    private void dokumentMitKlassifikation(String benutzer, String art, String klasse, List<String> werte)
            throws SQLException {
        String mandant = benutzer.substring(benutzer.indexOf('-') + 1);
        UUID id = Testdokumente.anlegen(datenbank.get(), new Testdokumente.Angaben(mandant, art, "NORMAL", false,
                Testanmeldung.sub(benutzer), null, "EINGANGSKORB"));
        eigeneDokumente.add(id);
        for (String wert : werte) {
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement einfuegen = verbindung.prepareStatement("insert into "
                         + new MandantCode(mandant).schema()
                         + ".dokument_klassifikation (dokument_id, klassifikation, wert) values (?, ?, ?)")) {
                einfuegen.setObject(1, id);
                einfuegen.setString(2, klasse);
                einfuegen.setString(3, wert);
                einfuegen.executeUpdate();
            }
        }
    }

    private ObjectNode art(String code) {
        for (JsonNode art : datei.path("arten")) {
            if (code.equals(art.path("code").asText())) {
                return (ObjectNode) art;
            }
        }
        throw new AssertionError("Art " + code + " fehlt in der Datei");
    }

    private ObjectNode klasse(String code) {
        for (JsonNode klasse : datei.path("klassifikationen")) {
            if (code.equals(klasse.path("code").asText())) {
                return (ObjectNode) klasse;
            }
        }
        throw new AssertionError("Klassifikation " + code + " fehlt in der Datei");
    }

    /** Spielt die Ausgangsdatei zweimal ein: Erst danach sind Zuordnungen weg, die eine Klassifikation hielten. */
    private void ausgangsdateiWiederherstellen() throws IOException {
        for (int lauf = 0; lauf < 2; lauf++) {
            Response stand = given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).when().get(KONFIGURATION);
            assertThat(stand.statusCode()).as(stand.asString()).isEqualTo(200);
            ObjectNode zurueck = ausgangsdatei.deepCopy();
            zurueck.put("basisVersion", stand.jsonPath().getInt("basisVersion"));
            Response einspielen = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .queryParam("bestaetigt",
                            "geschaeftsjahrEnde,chat.aufbewahrungMonate,webseitenChat.aufbewahrungTage,wissen.monatsbudgetTokens")
                    .header("X-Aenderungsgrund", "Testwerte wiederherstellen").contentType(ContentType.JSON)
                    .body(JSON.writeValueAsString(zurueck)).when().put(KONFIGURATION);
            assertThat(einspielen.statusCode()).as(einspielen.asString()).isEqualTo(200);
        }
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

    @Dann("ist die KI laut KI-Texte-Endpunkt {word}")
    public void kiFreigabe(String zustand) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().getBoolean("kiFreigegeben")).isEqualTo("freigegeben".equals(zustand));
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
    public void aufraeumen() throws SQLException, IOException {
        String benutzer = "inhaber-urinet";
        Testdokumente.loeschen(datenbank.get(), "urinet", eigeneDokumente);
        eigeneDokumente.clear();
        if (dateiWiederherstellen && ausgangsdatei != null) {
            ausgangsdateiWiederherstellen();
        }
        dateiWiederherstellen = false;
        ausgangsdatei = null;
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

    @After(order = -100)
    public void stepUpWiederherstellen() {
        if (urspruenglichesStepUp != null) {
            QuarkusMock.installMockForType(urspruenglichesStepUp, StepUp.class);
            urspruenglichesStepUp = null;
        }
    }

    @After(order = -200)
    public void grenzenWiederherstellen() {
        if (urspruenglicheGrenzen != null) {
            QuarkusMock.installMockForType(urspruenglicheGrenzen, VerwaltungGrenzen.class);
            urspruenglicheGrenzen = null;
        }
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
