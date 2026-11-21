package ch.urinet.archiv.benutzer.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class VerwaltungCacheHttpTest {

    @Inject
    AgroalDataSource datenbank;

    @Inject
    MandantProvisionierung provisionierung;

    @BeforeEach
    void mandantAnlegen() throws SQLException {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select 1 from public.mandant where code = ? and aktiv")) {
            abfrage.setString(1, "urinet");
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                if (!ergebnis.next()) {
                    provisionierung.anlegen("urinet", null, null);
                }
            }
        }
    }

    @Test
    void auchAnmeldungUndRechtefehlerTragenNoStore() {
        String pfad = "/api/v1/benutzer/cache-probe";
        given().when().get(pfad).then().statusCode(401).header("Cache-Control", containsString("no-store"));
        given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet")).when().get(pfad).then()
                .statusCode(403).header("Cache-Control", containsString("no-store"));
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).when().get(pfad).then()
                .statusCode(200).header("Cache-Control", containsString("no-store"));
    }
}
