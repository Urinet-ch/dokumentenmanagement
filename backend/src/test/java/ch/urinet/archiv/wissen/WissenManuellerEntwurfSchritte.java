package ch.urinet.archiv.wissen;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class WissenManuellerEntwurfSchritte {
    @Inject
    Instance<AgroalDataSource> datenbank;

    private final String pfad = "konzepte/manuell-" + UUID.randomUUID();
    private UUID entwurf;
    private int status;
    private Response antwort;

    @Wenn("der Inhaber einen manuellen Wissensentwurf ohne Titel anlegt")
    public void inhaberLegtEntwurfAn() {
        String id = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .contentType("application/json").body(eingabe())
                .when().post("/api/v1/wissensentwuerfe").then().statusCode(201).extract().path("id");
        entwurf = UUID.fromString(id);
    }

    @Dann("ist der Entwurf offen und hat den Befund «{word}»")
    public void entwurfHatBefund(String code) throws Exception {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("""
                     select count(*) from m_urinet.wissens_entwurf e
                     join m_urinet.wissens_aenderung a on a.entwurf_id = e.id
                     where e.id = ? and e.anlass = 'MANUELL' and e.status = 'OFFEN'
                       and a.befunde @> jsonb_build_array(jsonb_build_object('code', cast(? as text)))
                     """)) {
            befehl.setObject(1, entwurf);
            befehl.setString(2, code);
            try (var ergebnis = befehl.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                assertThat(ergebnis.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Wenn("der Treuhänder denselben manuellen Wissensentwurf anlegen möchte")
    public void treuhaenderLegtEntwurfAn() {
        status = given().auth().oauth2(Testanmeldung.token("treuhaender-urinet"))
                .contentType("application/json").body(eingabe())
                .when().post("/api/v1/wissensentwuerfe").statusCode();
    }

    @Dann("erhält er 403 und es entsteht kein weiterer Wissensentwurf")
    public void treuhaenderAbgewiesen() throws Exception {
        assertThat(status).isEqualTo(403);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(
                     "select count(*) from m_urinet.wissens_aenderung where pfad = ?")) {
            befehl.setString(1, pfad);
            try (var ergebnis = befehl.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                assertThat(ergebnis.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Wenn("der Inhaber diese Änderung ohne If-Match annehmen möchte")
    public void annehmenOhneVersion() {
        status = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .contentType("application/json").body("{}")
                .when().post("/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen").statusCode();
    }

    @Dann("wird die Annahme mit 428 abgelehnt")
    public void versionFehlt() {
        assertThat(status).isEqualTo(428);
    }

    @Wenn("der Inhaber die Änderung mit aktueller Version annehmen möchte")
    public void annehmenMitVersion() {
        String inhaber = Testanmeldung.token("inhaber-urinet");
        int etag = given().auth().oauth2(inhaber).when().get("/api/v1/wissensentwuerfe/" + entwurf)
                .then().statusCode(200).body("aenderungen[0].diff", notNullValue())
                .extract().path("aenderungen[0].etag");
        antwort = given().auth().oauth2(inhaber).header("If-Match", "\"" + etag + "\"")
                .contentType("application/json").body("{}")
                .when().post("/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen");
    }

    @Dann("wird die Annahme mit 422 und dem Befund «FELD_FEHLT» abgelehnt und bleibt offen")
    public void annahmeBleibtOffen() {
        antwort.then().statusCode(422).body("fehlercode", is("ENTWURF_BEFUND"))
                .body("befunde", hasItem("FELD_FEHLT"));
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .when().get("/api/v1/wissensentwuerfe/" + entwurf).then().statusCode(200)
                .body("aenderungen[0].status", is("OFFEN"));
    }

    @Wenn("der Inhaber die Fassung korrigiert und einzeln annimmt")
    public void korrigiertAnnehmen() {
        Map<String, Object> fassung = Map.of("titel", "Probe", "beschreibung", "Ein Satz.", "tags", List.of(),
                "quellen", List.of(), "inhalt", "");
        antwort = given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).header("If-Match", "\"0\"")
                .contentType("application/json").body(Map.of("fassung", fassung))
                .when().post("/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen");
    }

    @Dann("sind die Seite und ihre Version freigegeben und der Entscheid ist protokolliert")
    public void freigabeProtokolliert() throws Exception {
        antwort.then().statusCode(204);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("""
                     select a.status, s.version, s.freigegeben_von, p.benutzer,
                            p.details->'wissen'->>'seiteId' = s.id::text,
                            p.details->'wissen'->>'neueVersion',
                            exists (select 1 from public.auftrag o where o.typ = 'WISSEN_EMBEDDING'
                                    and o.nutzlast->>'seiteId' = s.id::text)
                     from m_urinet.wissens_aenderung a
                     join m_urinet.wissensseite s on s.id = a.seite_id
                     join m_urinet.wissensseite_version v on v.seite_id = s.id and v.version = s.version
                     join m_urinet.zugriff_protokoll p
                       on p.dokument_id = a.entwurf_id and p.aktion = 'WISSEN_ANNEHMEN'
                     where a.entwurf_id = ?
                     """)) {
            befehl.setObject(1, entwurf);
            try (var ergebnis = befehl.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                assertThat(ergebnis.getString(1)).isEqualTo("ANGENOMMEN");
                assertThat(ergebnis.getInt(2)).isEqualTo(1);
                assertThat(ergebnis.getString(3)).isEqualTo("human:" + ergebnis.getString(4));
                assertThat(ergebnis.getBoolean(5)).isTrue();
                assertThat(ergebnis.getString(6)).isEqualTo("1");
                assertThat(ergebnis.getBoolean(7)).isTrue();
                assertThat(ergebnis.next()).isFalse();
            }
        }
    }

    @After("@wissen-manuell")
    public void aufraeumen() throws Exception {
        if (entwurf == null) {
            return;
        }
        try (Connection verbindung = datenbank.get().getConnection()) {
            verbindung.setAutoCommit(false);
            try (var befehl = verbindung.createStatement()) {
                befehl.execute("set local session_replication_role = replica");
            }
            String seiten = "(select seite_id from m_urinet.wissens_aenderung "
                    + "where entwurf_id = ? and aktion = 'NEU' and seite_id is not null)";
            for (String sql : List.of(
                    "delete from public.auftrag where typ = 'WISSEN_EMBEDDING' and nutzlast->>'seiteId' in "
                            + "(select seite_id::text from m_urinet.wissens_aenderung where entwurf_id = ?)",
                    "delete from m_urinet.wissensseite_version_dokument where seite_id in " + seiten,
                    "delete from m_urinet.wissensseite_version where entwurf_id = ?",
                    "delete from m_urinet.wissens_log where entwurf_id = ?",
                    "delete from m_urinet.zugriff_protokoll where dokument_id = ?",
                    "delete from m_urinet.wissensseite_quelle where seite_id in " + seiten,
                    "delete from m_urinet.wissensseite_dokument where seite_id in " + seiten,
                    "delete from m_urinet.wissensseite_link where von_seite_id in " + seiten,
                    "delete from m_urinet.wissensseite where id in " + seiten)) {
                try (PreparedStatement befehl = verbindung.prepareStatement(sql)) {
                    befehl.setObject(1, entwurf);
                    befehl.executeUpdate();
                }
            }
            for (String tabelle : new String[]{"wissens_aenderung", "wissens_entwurf"}) {
                try (PreparedStatement befehl = verbindung.prepareStatement("delete from m_urinet." + tabelle
                        + (tabelle.equals("wissens_aenderung") ? " where entwurf_id = ?" : " where id = ?"))) {
                    befehl.setObject(1, entwurf);
                    befehl.executeUpdate();
                }
            }
            verbindung.commit();
        }
    }

    private Map<String, Object> eingabe() {
        return Map.of("pfad", pfad, "typ", "Konzept", "titel", "", "beschreibung", "Probe", "inhalt", "Probe.");
    }
}
