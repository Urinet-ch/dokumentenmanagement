package ch.urinet.archiv.dokumentart;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Req 9.1, 9.4, 9.5 und 2.17 über {@code /api/v1/dokumentarten}. Szenarien legen nur Arten mit dem Präfix
 * {@value #PRAEFIX} an; sie werden nach jedem Szenario in allen Mandanten gelöscht, die Seed-Arten bleiben unberührt.
 */
public class DokumentartVerwaltungSchritte {

    private static final String PFAD = "/api/v1/dokumentarten";
    private static final String PRAEFIX = "PROBE_DA_";
    private static final List<String> MANDANTEN = List.of("urinet", "vhf-gsk");

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    DokumentZaehlerAttrappe zaehler;

    private Response antwort;
    private boolean benutzt;

    @After
    public void aufraeumen() throws SQLException {
        zaehler.leeren();
        if (!benutzt) {
            return;
        }
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String mandant : MANDANTEN) {
                try (PreparedStatement loeschen = verbindung.prepareStatement("delete from "
                        + new MandantCode(mandant).schema() + ".dokumentart where code like ?")) {
                    loeschen.setString(1, PRAEFIX + "%");
                    loeschen.executeUpdate();
                }
                // Szenarien ändern an Seed-Arten nur «aktiv» (z. B. Req 9.4 an KORRESPONDENZ)
                try (PreparedStatement aktivieren = verbindung.prepareStatement("update "
                        + new MandantCode(mandant).schema() + ".dokumentart set aktiv = true where not aktiv and code not like ?")) {
                    aktivieren.setString(1, PRAEFIX + "%");
                    aktivieren.executeUpdate();
                }
            }
        }
    }

    @Wenn("«{word}» eine Dokumentart anlegt:")
    public void anlegen(String benutzer, String body) {
        benutzt = true;
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON).body(body)
                .when().post(PFAD);
    }

    @Wenn("«{word}» die Dokumentart «{word}» ändert:")
    public void aendern(String benutzer, String code, String body) {
        benutzt = true;
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON).body(body)
                .when().patch(PFAD + "/" + code);
    }

    @Wenn("«{word}» die Dokumentart «{word}» löscht")
    public void loeschen(String benutzer, String code) {
        benutzt = true;
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().delete(PFAD + "/" + code);
    }

    @Wenn("«{word}» die Dokumentarten auflistet")
    public void auflisten(String benutzer) {
        benutzt = true;
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get(PFAD);
    }

    @Angenommen("die Dokumentart «{word}» hat {int} Dokument(e)")
    public void dokumente(String code, int anzahl) {
        benutzt = true;
        zaehler.setzen(code, anzahl);
    }

    @Dann("antwortet die Dokumentart-Verwaltung mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("antwortet die Dokumentart-Verwaltung mit Status {int} und Fehlercode «{word}»")
    public void fehlercode(int status, String fehlercode) {
        assertThat(ProblemDetails.pruefen(antwort, status)).as(antwort.asString()).isEqualTo(fehlercode);
    }

    /** Werte als Text: Listen wie {@code [finanz, vertrag]}, fehlende Werte als {@code null}. */
    @Dann("hat die Dokumentart in der Antwort diese Werte:")
    public void werte(DataTable tabelle) {
        pruefen(antwort.jsonPath().getMap(""), tabelle);
    }

    @Dann("enthält die Liste der Dokumentarten «{word}» mit diesen Werten:")
    public void listeMitWerten(String code, DataTable tabelle) {
        pruefen(art(code), tabelle);
    }

    @Dann("enthält die Liste der Dokumentarten «{word}» nicht")
    public void listeOhne(String code) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().<String>getList("code")).doesNotContain(code);
    }

    private Map<String, Object> art(String code) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        List<Map<String, Object>> arten = antwort.jsonPath().getList("");
        return arten.stream().filter(a -> code.equals(a.get("code"))).findFirst()
                .orElseThrow(() -> new AssertionError("Art " + code + " fehlt in der Liste"));
    }

    private static void pruefen(Map<String, Object> art, DataTable tabelle) {
        for (Map.Entry<String, String> zeile : tabelle.asMap().entrySet()) {
            Object wert = art;
            for (String teil : zeile.getKey().split("\\.")) {
                wert = wert instanceof Map<?, ?> m ? m.get(teil) : null;
            }
            assertThat(String.valueOf(wert)).as(zeile.getKey()).isEqualTo(zeile.getValue());
        }
    }
}
