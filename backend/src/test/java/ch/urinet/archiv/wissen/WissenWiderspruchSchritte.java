package ch.urinet.archiv.wissen;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

public class WissenWiderspruchSchritte {
    @Inject
    Instance<AgroalDataSource> datenbank;

    private final List<UUID> dokumente = new ArrayList<>();
    private final List<UUID> entwuerfe = new ArrayList<>();
    private final String pfad = "konzepte/widerspruch-" + UUID.randomUUID();
    private UUID seite;
    private UUID entwurf;
    private Response antwort;
    private static final String BLOCK = "> ⚠️ Widerspruch: Alte Frist.[^alt] Neue Frist.[^neu]";
    private static final String UNABHAENGIG = "\n\nUnabhängige Aussage.[^dritte]";

    @Angenommen("eine Wissensseite enthält eine alte belegte Aussage und eine unabhängige Aussage")
    public void basis() throws Exception {
        for (String jahr : List.of("2000", "2026", "2005")) {
            UUID id = Testdokumente.anlegen(datenbank.get(),
                    new Testdokumente.Angaben("urinet", "WISSENSQUELLE", "NORMAL", true, "probe", null, "ARCHIVIERT"));
            dokumente.add(id);
            try (Connection c = datenbank.get().getConnection()) {
                sql(c, "update m_urinet.dokument set seiten = 1, volltext = 'Synthetische Quelle' || chr(12), "
                                + "datum = cast(? as date) where id = ?",
                        jahr + "-01-01", id);
            }
        }
        entwurf = anlegen("Alte Frist.[^alt]" + UNABHAENGIG, quellen(false));
        annehmen(Map.of()).then().statusCode(204);
        seite = UUID.fromString(given().auth().oauth2(token()).get("/api/v1/wissensentwuerfe/" + entwurf)
                .then().statusCode(200).extract().path("aenderungen[0].seiteId"));
    }

    @Wenn("der Inhaber eine widersprechende neue Quelle als Widerspruchsblock freigibt")
    public void widerspruch() {
        entwurf = anlegen(BLOCK + UNABHAENGIG, quellen(true));
        given().auth().oauth2(token()).get("/api/v1/wissensentwuerfe/" + entwurf)
                .then().statusCode(200).body("aenderungen[0].diff.widersprueche.neu.size()", is(1));
        annehmen(Map.of()).then().statusCode(204);
    }

    @Dann("bleiben beide Quellen mit ihren Daten und dem Hinweis «Widerspruch offen» sichtbar")
    public void beideQuellen() {
        given().auth().oauth2(token()).get("/api/v1/wissensseiten/" + seite).then().statusCode(200)
                .body("widerspruchOffen", is(true)).body("inhaltHtml", containsString("Alte Frist."))
                .body("inhaltHtml", containsString("Neue Frist.")).body("quellen.size()", is(3))
                .body("quellen.find { it.schluessel == 'alt' }.dokumentDatum", is("2000-01-01"))
                .body("quellen.find { it.schluessel == 'neu' }.dokumentDatum", is("2026-01-01"))
                .body("freigegebenAm", containsString("T"));
    }

    @Wenn("der Inhaber den Block ohne Grund auflösen möchte")
    public void ohneGrund() {
        entwurf = anlegen("Neue Frist.[^neu]" + UNABHAENGIG, quellen(true));
        given().auth().oauth2(token()).get("/api/v1/wissensentwuerfe/" + entwurf)
                .then().statusCode(200).body("aenderungen[0].diff.entfernteWidersprueche.size()", is(1))
                .body("aenderungen[0].diff.entfernteWidersprueche[0].text", containsString("Alte Frist."))
                .body("aenderungen[0].diff.entfernteWidersprueche[0].fussnoten", hasItem("alt"))
                .body("aenderungen[0].diff.entfernteWidersprueche[0].fussnoten", hasItem("neu"));
        antwort = annehmen(Map.of());
    }

    @Dann("wird die Auflösung mit 422 «PFLICHTFELD_FEHLT» abgelehnt")
    public void grundFehlt() {
        antwort.then().statusCode(422).body("fehlercode", is("PFLICHTFELD_FEHLT"));
        annehmen(Map.of("grund", " ")).then().statusCode(422).body("fehlercode", is("PFLICHTFELD_FEHLT"));
    }

    @Wenn("der Inhaber mit Grund zugleich die unabhängige Aussage entfernt")
    public void zuVielEntfernt() {
        antwort = annehmen(Map.of("grund", "Neue Frist gilt",
                "fassung", fassung("Neue Frist.[^neu]", quellen(true))));
    }

    @Dann("bleibt die Annahme durch «AUSSAGE_ENTFERNT» gesperrt")
    public void gesperrt() {
        antwort.then().statusCode(422).body("befunde", hasItem("AUSSAGE_ENTFERNT"));
        given().auth().oauth2(token()).get("/api/v1/wissensseiten/" + seite)
                .then().statusCode(200).body("version", is(2)).body("widerspruchOffen", is(true));
    }

    @Wenn("der Inhaber nur den Widerspruch mit Grund auflöst")
    public void mitGrund() {
        annehmen(Map.of("grund", "Neue Frist gilt")).then().statusCode(204);
    }

    @Dann("sind der Grund und «Widerspruch aufgelöst» protokolliert und die alte Version bleibt erhalten")
    public void aufgeloest() throws Exception {
        given().auth().oauth2(token()).get("/api/v1/wissensseiten/" + seite).then().statusCode(200)
                .body("version", is(3)).body("widerspruchOffen", is(false))
                .body("inhaltHtml", containsString("Unabhängige Aussage."));
        given().auth().oauth2(token()).get("/api/v1/wissensseiten/" + seite + "/versionen/2")
                .then().statusCode(200).body("inhaltHtml", containsString("Alte Frist."))
                .body("daten.widerspruch_offen", is(true));
        given().auth().oauth2(token()).get("/api/v1/wissensseiten/" + seite + "/versionen/3")
                .then().statusCode(200).body("grund", is("Neue Frist gilt"));
        given().auth().oauth2(token()).get("/api/v1/wissensbasis/log").then().statusCode(200)
                .body("eintraege.find { it.entwurfId == '" + entwurf + "' }.text",
                        containsString("Widerspruch aufgelöst"));
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement s = c.prepareStatement("""
                     select count(*) from m_urinet.wissensseite_version
                     where seite_id = ? and version = 3 and grund = 'Neue Frist gilt'
                     """)) {
            s.setObject(1, seite);
            try (var r = s.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getInt(1)).isEqualTo(1);
            }
        }
    }

    private UUID anlegen(String inhalt, List<Map<String, Object>> quellen) {
        Map<String, Object> eingabe = new java.util.HashMap<>(fassung(inhalt, quellen));
        eingabe.put("pfad", pfad);
        eingabe.put("typ", "Konzept");
        if (seite != null) {
            eingabe.put("seiteId", seite);
        }
        UUID id = UUID.fromString(given().auth().oauth2(token()).contentType("application/json").body(eingabe)
                .post("/api/v1/wissensentwuerfe").then().statusCode(201).extract().path("id"));
        entwuerfe.add(id);
        return id;
    }

    private Response annehmen(Map<String, Object> eingabe) {
        int etag = given().auth().oauth2(token()).get("/api/v1/wissensentwuerfe/" + entwurf)
                .then().statusCode(200).extract().path("aenderungen[0].etag");
        return given().auth().oauth2(token()).header("If-Match", "\"" + etag + "\"")
                .contentType("application/json").body(eingabe)
                .post("/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen");
    }

    private Map<String, Object> fassung(String inhalt, List<Map<String, Object>> quellen) {
        return Map.of("titel", "Synthetische Fristen", "beschreibung", "Fristen im Test.", "tags", List.of(),
                "quellen", quellen, "inhalt", inhalt);
    }

    private List<Map<String, Object>> quellen(boolean mitNeu) {
        List<Map<String, Object>> quellen = new ArrayList<>();
        for (int i : mitNeu ? List.of(0, 1, 2) : List.of(0, 2)) {
            quellen.add(Map.of("schluessel", List.of("alt", "neu", "dritte").get(i),
                    "dokumentId", dokumente.get(i), "seiteVon", 1));
        }
        return quellen;
    }

    private String token() {
        return Testanmeldung.token("inhaber-urinet");
    }

    @After("@wissen-widerspruch")
    public void aufraeumen() throws Exception {
        try (Connection c = datenbank.get().getConnection()) {
            c.setAutoCommit(false);
            sql(c, "set local session_replication_role = replica");
            if (seite != null) {
                sql(c, "delete from public.auftrag where typ = 'WISSEN_EMBEDDING' and nutzlast->>'seiteId' = ?",
                        seite.toString());
                for (String tabelle : List.of("wissensseite_version_dokument", "wissensseite_version",
                        "wissensseite_quelle", "wissensseite_dokument")) {
                    sql(c, "delete from m_urinet." + tabelle + " where seite_id = ?", seite);
                }
                sql(c, "delete from m_urinet.wissensseite_link where von_seite_id = ?", seite);
                sql(c, "delete from m_urinet.wissensseite where id = ?", seite);
            }
            for (UUID id : entwuerfe) {
                sql(c, "delete from m_urinet.wissens_log where entwurf_id = ?", id);
                sql(c, "delete from m_urinet.zugriff_protokoll where dokument_id = ?", id);
                sql(c, "delete from m_urinet.wissens_aenderung where entwurf_id = ?", id);
                sql(c, "delete from m_urinet.wissens_entwurf where id = ?", id);
            }
            c.commit();
        }
        Testdokumente.loeschen(datenbank.get(), "urinet", dokumente);
    }

    private static void sql(Connection c, String sql, Object... parameter) throws Exception {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameter.length; i++) {
                s.setObject(i + 1, parameter[i]);
            }
            s.execute();
        }
    }
}
