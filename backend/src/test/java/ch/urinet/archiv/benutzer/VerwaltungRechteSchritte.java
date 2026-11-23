package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.Before;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class VerwaltungRechteSchritte {
    @Inject AgroalDataSource datenbank;
    @Inject MandantProvisionierung provisionierung;

    private String benutzer;
    private String schema;
    private String aktion;
    private long vorher;
    private Response antwort;

    @Before("@verwaltung-rechte-http")
    public void mandantenVorbereiten() throws SQLException {
        for (String code : List.of("urinet", "vhf-gsk")) {
            try (Connection verbindung = datenbank.getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select 1 from public.mandant where code = ? and aktiv")) {
                abfrage.setString(1, code);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        provisionierung.anlegen(code, null, null);
                    }
                }
            }
        }
    }

    @Angenommen("«{word}» ist im Test-Realm angemeldet")
    public void anmelden(String rolle) {
        boolean verband = rolle.equals("geschaeftsstelle");
        benutzer = rolle + (verband ? "-vhf-gsk" : "-urinet");
        schema = verband ? "m_vhf_gsk" : "m_urinet";
        Testanmeldung.token(benutzer);
    }

    @Wenn("die Person die Verwaltungsaktion «{word}» über HTTP aufruft")
    public void aufrufen(String recht) throws SQLException {
        aktion = recht;
        vorher = verweigerungen();
        var anfrage = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType("application/json");
        String ziel = "/api/v1/benutzer/" + UUID.randomUUID();
        antwort = switch (recht) {
            case "A20" -> anfrage.get("/api/v1/mandant/einstellungen");
            case "A21" -> anfrage.get("/api/v1/benutzer");
            case "A22" -> anfrage.body("{\"grund\":\"SICHERHEIT\"}").post(ziel + "/sperren");
            case "A23" -> anfrage.header("If-Match", "\"" + "0".repeat(64) + "\"")
                    .body("{\"rolle\":\"mitarbeiter\"}").put(ziel + "/rolle");
            default -> throw new IllegalArgumentException("Unbekannte Verwaltungsaktion: " + recht);
        };
    }

    @Dann("wird der Aufruf mit «NICHT_ERLAUBT» und einem Verweigerungseintrag beantwortet")
    public void verweigert() throws SQLException {
        assertThat(antwort.statusCode()).isEqualTo(403);
        assertThat(antwort.<String>path("fehlercode")).isEqualTo("NICHT_ERLAUBT");
        assertThat(verweigerungen()).isEqualTo(vorher + 1);
    }

    private long verweigerungen() throws SQLException {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select count(*) from " + schema + ".zugriff_protokoll "
                             + "where aktion = 'VERWEIGERT' and benutzer = ? and details->>'grund' = ?")) {
            abfrage.setString(1, Testanmeldung.sub(benutzer));
            abfrage.setString(2, aktion);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                return zeile.getLong(1);
            }
        }
    }
}
