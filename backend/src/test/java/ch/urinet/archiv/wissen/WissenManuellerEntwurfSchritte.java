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
    private UUID zweiterEntwurf;
    private UUID dritterEntwurf;
    private UUID seite;
    private UUID zitierteSeite;
    private int status;
    private Response antwort;
    private List<Long> vorherZaehler;

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
    public void treuhaenderLegtEntwurfAn() throws Exception {
        vorherZaehler = zaehler();
        status = given().auth().oauth2(Testanmeldung.token("treuhaender-urinet"))
                .contentType("application/json").body(eingabe())
                .when().post("/api/v1/wissensentwuerfe").statusCode();
    }

    @Dann("erhält er 403 und es entsteht kein weiterer Wissensentwurf")
    public void treuhaenderAbgewiesen() throws Exception {
        assertThat(status).isEqualTo(403);
        // Datenbank unverändert; im Zugriffsprotokoll nur kein WISSEN_*-Eintrag (ein VERWEIGERT-Eintrag nach
        // Req 14.4 ist ausdrücklich erlaubt, Auslegung in tasks.md 14.2)
        assertThat(zaehler()).isEqualTo(vorherZaehler);
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

    /** Zeilenzahlen von wissens_entwurf, wissens_aenderung, wissens_log und WISSEN_*-Zugriffsprotokoll. */
    private List<Long> zaehler() throws Exception {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("""
                     select (select count(*) from m_urinet.wissens_entwurf),
                            (select count(*) from m_urinet.wissens_aenderung),
                            (select count(*) from m_urinet.wissens_log),
                            (select count(*) from m_urinet.zugriff_protokoll where aktion like 'WISSEN\\_%')
                     """);
             var ergebnis = befehl.executeQuery()) {
            assertThat(ergebnis.next()).isTrue();
            return List.of(ergebnis.getLong(1), ergebnis.getLong(2), ergebnis.getLong(3), ergebnis.getLong(4));
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
                                    and o.nutzlast->>'seiteId' = s.id::text),
                            e.status, s.id
                     from m_urinet.wissens_aenderung a
                     join m_urinet.wissens_entwurf e on e.id = a.entwurf_id
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
                assertThat(ergebnis.getString(8)).isEqualTo("ERLEDIGT");
                seite = ergebnis.getObject(9, UUID.class);
                assertThat(ergebnis.next()).isFalse();
            }
        }
    }

    @Wenn("der Inhaber zwei Änderungen derselben Seite erstellt und die erste annimmt")
    public void zweiAenderungenAnlegen() {
        String token = Testanmeldung.token("inhaber-urinet");
        Map<String, Object> eingabe = Map.of("seiteId", seite, "pfad", pfad, "typ", "Konzept",
                "titel", "Probe", "beschreibung", "Ein Satz.", "tags", List.of(), "quellen", List.of(),
                "inhalt", "");
        zweiterEntwurf = UUID.fromString(given().auth().oauth2(token).contentType("application/json").body(eingabe)
                .when().post("/api/v1/wissensentwuerfe").then().statusCode(201).extract().path("id"));
        dritterEntwurf = UUID.fromString(given().auth().oauth2(token).contentType("application/json").body(eingabe)
                .when().post("/api/v1/wissensentwuerfe").then().statusCode(201).extract().path("id"));
        given().auth().oauth2(token).header("If-Match", "\"0\"").contentType("application/json").body("{}")
                .when().post("/api/v1/wissensentwuerfe/" + dritterEntwurf + "/aenderungen/1/annehmen")
                .then().statusCode(204);
    }

    @Dann("wird die zweite Änderung als veraltet abgelehnt und kann neu erzeugt werden")
    public void veralteteAenderungNeuErzeugen() {
        String token = Testanmeldung.token("inhaber-urinet");
        String url = "/api/v1/wissensentwuerfe/" + zweiterEntwurf + "/aenderungen/1";
        given().auth().oauth2(token).header("If-Match", "\"0\"").contentType("application/json").body("{}")
                .when().post(url + "/annehmen").then().statusCode(412).body("fehlercode", is("VERSION_KONFLIKT"));
        int etag = given().auth().oauth2(token).when().get("/api/v1/wissensentwuerfe/" + zweiterEntwurf)
                .then().statusCode(200).body("aenderungen[0].status", is("VERALTET"))
                .extract().path("aenderungen[0].etag");
        given().auth().oauth2(token).header("If-Match", "\"" + etag + "\"")
                .when().post(url + "/neu-erzeugen").then().statusCode(202);
    }

    @Wenn("ein Mitarbeiter einen entschiedenen Analysevorschlag hat")
    public void analysevorschlagAnlegen() throws Exception {
        entwurf = UUID.randomUUID();
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement e = c.prepareStatement("""
                     insert into m_urinet.wissens_entwurf (id, anlass, status, erstellt_von, erstellt)
                     values (?, 'ANALYSE', 'ABGELEHNT', ?, now())
                     """);
             PreparedStatement a = c.prepareStatement("""
                     insert into m_urinet.wissens_aenderung
                         (entwurf_id, nr, aktion, pfad, daten, inhalt, diff, status, grund)
                     values (?, 1, 'NEU', ?, '{}'::jsonb, 'Probe', '{"inhalt":"Probe"}'::jsonb, 'VERWORFEN', 'Grund')
                     """)) {
            e.setObject(1, entwurf);
            e.setString(2, Testanmeldung.sub("mitarbeiter-urinet"));
            e.executeUpdate();
            a.setObject(1, entwurf);
            a.setString(2, pfad);
            a.executeUpdate();
        }
    }

    @Dann("sieht er im Entwurfszugriff den Entscheid ohne Fassung und Diff")
    public void analysevorschlagNurStatus() {
        var json = given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet"))
                .when().get("/api/v1/wissensentwuerfe/" + entwurf)
                .then().statusCode(200).extract().jsonPath();
        assertThat(json.getString("status")).isEqualTo("ABGELEHNT");
        assertThat(json.getString("entscheidungen[0].status")).isEqualTo("VERWORFEN");
        assertThat(json.getString("entscheidungen[0].grund")).isEqualTo("Grund");
        assertThat(json.<String, Object>getMap("$")).containsOnlyKeys("id", "status", "entscheidungen");
    }

    @Wenn("der Inhaber eine Übersicht mit einer internen zitierten Seite annehmen möchte")
    public void ueberblickAnnehmen() throws Exception {
        zitierteSeite = UUID.randomUUID();
        entwurf = UUID.randomUUID();
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement s = c.prepareStatement("""
                     insert into m_urinet.wissensseite
                         (id, pfad, typ, titel, beschreibung, vertraulichkeit, vertraulichkeit_mindest,
                          inhalt, version, erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am)
                     values (?, ?, 'Konzept', 'Probe', 'Probe', 'INTERN', 'INTERN', '', 1,
                             'probe', now(), 'probe', now())
                     """);
             PreparedStatement e = c.prepareStatement("""
                     insert into m_urinet.wissens_entwurf (id, anlass, status, erstellt_von, erstellt)
                     values (?, 'MANUELL', 'OFFEN', 'probe', now())
                     """);
             PreparedStatement a = c.prepareStatement("""
                     insert into m_urinet.wissens_aenderung
                         (entwurf_id, nr, aktion, pfad, daten, inhalt, diff, status)
                     values (?, 1, 'NEU', 'ueberblick', cast(? as jsonb), 'Aussage.[^q1]', '{}'::jsonb, 'OFFEN')
                     """)) {
            s.setObject(1, zitierteSeite);
            s.setString(2, pfad);
            s.executeUpdate();
            e.setObject(1, entwurf);
            e.executeUpdate();
            a.setObject(1, entwurf);
            a.setString(2, """
                    {"typ":"Synthese","titel":"Überblick","beschreibung":"Ein Satz.","tags":[],
                     "status":"AKTIV","quellen":[{"schluessel":"q1","quellSeiteId":"%s"}],
                     "vertraulichkeit":"NORMAL","inhalt":"Aussage.[^q1]"}
                    """.formatted(zitierteSeite));
            a.executeUpdate();
        }
        antwort = given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).header("If-Match", "\"0\"")
                .contentType("application/json").body("{\"vertraulichkeit\":\"NORMAL\"}")
                .when().post("/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen");
    }

    @Dann("wird die Freigabe mit dem Befund «STUFE_UEBERBLICK» abgelehnt")
    public void ueberblickAbgelehnt() {
        antwort.then().statusCode(422).body("befunde", hasItem("STUFE_UEBERBLICK"));
    }

    @Wenn("der Inhaber die korrigierte Fassung zuerst als PERSONAL und dann als INTERN annehmen möchte")
    public void vertraulichkeitErhoehen() {
        Map<String, Object> fassung = Map.of("titel", "Probe", "beschreibung", "Ein Satz.", "tags", List.of(),
                "quellen", List.of(), "inhalt", "");
        String url = "/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen";
        String token = Testanmeldung.token("inhaber-urinet");
        given().auth().oauth2(token).header("If-Match", "\"0\"")
                .contentType("application/json").body(Map.of("fassung", fassung, "vertraulichkeit", "PERSONAL"))
                .when().post(url).then().statusCode(422).body("fehlercode", is("VERTRAULICHKEIT_UNGUELTIG"));
        antwort = given().auth().oauth2(token).header("If-Match", "\"0\"")
                .contentType("application/json").body(Map.of("fassung", fassung, "vertraulichkeit", "INTERN"))
                .when().post(url);
    }

    @Dann("wird PERSONAL abgelehnt und die erhöhte Mindeststufe INTERN gespeichert")
    public void internGespeichert() throws Exception {
        antwort.then().statusCode(204);
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("""
                     select s.id, s.vertraulichkeit_mindest from m_urinet.wissensseite s
                     join m_urinet.wissens_aenderung a on a.seite_id = s.id where a.entwurf_id = ?
                     """)) {
            sql.setObject(1, entwurf);
            try (var rs = sql.executeQuery()) {
                assertThat(rs.next()).isTrue();
                seite = rs.getObject(1, UUID.class);
                assertThat(rs.getString(2)).isEqualTo("INTERN");
            }
        }
    }

    @Wenn("der Inhaber die Seite erneut ändern und auf NORMAL senken möchte")
    public void vertraulichkeitSenken() {
        String token = Testanmeldung.token("inhaber-urinet");
        Map<String, Object> eingabe = Map.of("seiteId", seite, "pfad", pfad, "typ", "Konzept",
                "titel", "Probe", "beschreibung", "Ein Satz.", "tags", List.of(), "quellen", List.of(),
                "inhalt", "");
        zweiterEntwurf = UUID.fromString(given().auth().oauth2(token).contentType("application/json").body(eingabe)
                .when().post("/api/v1/wissensentwuerfe").then().statusCode(201).extract().path("id"));
        antwort = given().auth().oauth2(token).header("If-Match", "\"0\"")
                .contentType("application/json").body(Map.of("vertraulichkeit", "NORMAL"))
                .when().post("/api/v1/wissensentwuerfe/" + zweiterEntwurf + "/aenderungen/1/annehmen");
    }

    @Dann("wird die Senkung abgelehnt und die Mindeststufe bleibt INTERN")
    public void senkungAbgelehnt() throws Exception {
        antwort.then().statusCode(422).body("fehlercode", is("VERTRAULICHKEIT_ZU_TIEF"));
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement(
                     "select vertraulichkeit_mindest from m_urinet.wissensseite where id = ?")) {
            sql.setObject(1, seite);
            try (var rs = sql.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("INTERN");
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
            for (UUID weiterer : new UUID[]{zweiterEntwurf, dritterEntwurf}) {
                if (weiterer == null) {
                    continue;
                }
                try (PreparedStatement befehl = verbindung.prepareStatement(
                        "delete from public.auftrag where typ = 'WISSEN_INGEST' and nutzlast->>'entwurfId' = ?")) {
                    befehl.setString(1, weiterer.toString());
                    befehl.executeUpdate();
                }
                for (String tabelle : List.of("wissensseite_version", "wissens_log", "zugriff_protokoll")) {
                    try (PreparedStatement befehl = verbindung.prepareStatement("delete from m_urinet." + tabelle
                            + (tabelle.equals("zugriff_protokoll") ? " where dokument_id = ?" : " where entwurf_id = ?"))) {
                        befehl.setObject(1, weiterer);
                        befehl.executeUpdate();
                    }
                }
                for (String tabelle : List.of("wissens_aenderung", "wissens_entwurf")) {
                    try (PreparedStatement befehl = verbindung.prepareStatement("delete from m_urinet." + tabelle
                            + (tabelle.equals("wissens_aenderung") ? " where entwurf_id = ?" : " where id = ?"))) {
                        befehl.setObject(1, weiterer);
                        befehl.executeUpdate();
                    }
                }
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
            if (zitierteSeite != null) {
                try (PreparedStatement befehl = verbindung.prepareStatement(
                        "delete from m_urinet.wissensseite where id = ?")) {
                    befehl.setObject(1, zitierteSeite);
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
