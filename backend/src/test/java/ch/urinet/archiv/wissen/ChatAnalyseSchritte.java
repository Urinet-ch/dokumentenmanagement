package ch.urinet.archiv.wissen;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class ChatAnalyseSchritte {
    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    MandantProvisionierung provisionierung;

    private UUID gespraech;
    private UUID entwurf;
    private long nachricht;
    private Response antwort;

    @Before("@wissen-analyse")
    public void mandantAnlegen() throws SQLException {
        boolean vorhanden;
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select 1 from public.mandant where code = 'urinet'");
             ResultSet zeile = abfrage.executeQuery()) {
            vorhanden = zeile.next();
        }
        if (!vorhanden) {
            provisionierung.anlegen("urinet", null, null);
        }
    }

    @After("@wissen-analyse")
    public void aufraeumen() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            if (entwurf != null) {
                loeschen(verbindung, "delete from m_urinet.wissens_aenderung where entwurf_id = ?", entwurf);
                loeschen(verbindung, "delete from m_urinet.wissens_entwurf where id = ?", entwurf);
            }
            if (gespraech != null) {
                loeschen(verbindung, "delete from m_urinet.chat_gespraech where id = ?", gespraech);
            }
        }
    }

    @Angenommen("eine eigene Chatantwort hat ein zulässiges Werkzeug mit internem Kontext")
    public void zulaessigeAntwort() throws SQLException {
        anlegen("wissen_index", "INTERN");
    }

    @Angenommen("eine eigene Chatantwort hat ein unzulässiges Werkzeug")
    public void unzulaessigeAntwort() throws SQLException {
        anlegen("kennzahl", "NORMAL");
    }

    private void anlegen(String werkzeug, String stufe) throws SQLException {
        gespraech = UUID.randomUUID();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement gespraechAnlegen = verbindung.prepareStatement(
                     "insert into m_urinet.chat_gespraech "
                             + "(id, benutzer, titel, erstellt, geaendert, verlauf_vollstaendig) "
                             + "values (?, ?, 'Probe', now(), now(), true)");
             PreparedStatement nachrichtAnlegen = verbindung.prepareStatement(
                     "insert into m_urinet.chat_nachricht "
                             + "(gespraech_id, rolle, inhalt, werkzeuge, quellen, status, zeit, kontext_vollstaendig) "
                             + "values (?, 'ASSISTENT', 'Ein synthetischer Satz.', "
                             + "jsonb_build_array(jsonb_build_object('name', ?, 'kontextstufe', ?, "
                             + "'dokumentIds', jsonb_build_array(), 'parameter', jsonb_build_object())), "
                             + "'[]'::jsonb, 'OK', now(), true) returning id")) {
            gespraechAnlegen.setObject(1, gespraech);
            gespraechAnlegen.setString(2, Testanmeldung.sub("mitarbeiter-urinet"));
            gespraechAnlegen.executeUpdate();
            nachrichtAnlegen.setObject(1, gespraech);
            nachrichtAnlegen.setString(2, werkzeug);
            nachrichtAnlegen.setString(3, stufe);
            try (ResultSet zeile = nachrichtAnlegen.executeQuery()) {
                zeile.next();
                nachricht = zeile.getLong(1);
            }
        }
    }

    @Wenn("der Mitarbeiter diese Antwort als Analyse vorschlägt")
    public void mitarbeiterSchlaegtVor() {
        vorschlagen("mitarbeiter-urinet");
    }

    @Wenn("der Inhaber die fremde Antwort als Analyse vorschlägt")
    public void inhaberSchlaegtVor() {
        vorschlagen("inhaber-urinet");
    }

    private void vorschlagen(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType("application/json")
                .body(Map.of("nachrichtId", Long.toString(nachricht)))
                .when().post("/api/v1/wissensentwuerfe/analyse");
        if (antwort.statusCode() == 201) {
            entwurf = UUID.fromString(antwort.path("id"));
        }
    }

    @Dann("ist der Analyseentwurf offen, intern und wegen fehlender Belege gesperrt")
    public void entwurfPruefen() {
        antwort.then().statusCode(201);
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .when().get("/api/v1/wissensentwuerfe/" + entwurf).then().statusCode(200)
                .body("entwurf.anlass", is("ANALYSE")).body("entwurf.status", is("OFFEN"))
                .body("aenderungen[0].vertraulichkeitKontext", is("INTERN"))
                .body("aenderungen[0].befunde.code", hasItem("AUSSAGE_OHNE_BELEG"));
    }

    @Und("der Mitarbeiter sieht nur Status und Entscheid des Vorschlags")
    public void statusPruefen() {
        given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet"))
                .when().get("/api/v1/wissensentwuerfe/" + entwurf).then().statusCode(200)
                .body("status", is("OFFEN")).body("$", not(hasKey("aenderungen")));
    }

    @Dann("wird der Analysevorschlag mit {int} abgelehnt")
    public void abgelehnt(int status) {
        antwort.then().statusCode(status);
    }

    private static void loeschen(Connection verbindung, String sql, UUID id) throws SQLException {
        try (PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setObject(1, id);
            befehl.executeUpdate();
        }
    }
}
