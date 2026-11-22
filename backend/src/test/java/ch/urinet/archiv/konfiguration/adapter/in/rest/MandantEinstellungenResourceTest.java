package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class MandantEinstellungenResourceTest {
    private static final String PFAD = "/api/v1/mandant/einstellungen";

    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenbank;

    @Test
    void rechteVersionRisikoUndValidierungWerdenInDieserReihenfolgeGeprueft() throws Exception {
        if (!imRegister("urinet")) {
            provisionierung.anlegen("urinet", null, null);
        }
        String inhaber = Testanmeldung.token("inhaber-urinet");
        String mitarbeiter = Testanmeldung.token("mitarbeiter-urinet");
        Response export = given().auth().oauth2(inhaber).get("/api/v1/konfiguration");
        assertThat(export.statusCode()).as(export.asString()).isEqualTo(200);
        String etag = export.getHeader("ETag");
        String ungültigUndRiskant = "{\"chat\":{\"aufbewahrungMonate\":0}}";

        assertThat(given().contentType("application/merge-patch+json").body(ungültigUndRiskant)
                .patch(PFAD).statusCode()).isEqualTo(401);
        assertThat(given().auth().oauth2(mitarbeiter).contentType("application/merge-patch+json")
                .body(ungültigUndRiskant).patch(PFAD).statusCode()).isEqualTo(403);
        assertThat(patch(inhaber, null, "{").statusCode()).isEqualTo(428);
        assertThat(patch(inhaber, "\"0\"", ungültigUndRiskant).statusCode()).isEqualTo(412);
        Response riskant = patch(inhaber, etag, ungültigUndRiskant);
        assertThat(riskant.statusCode()).as(riskant.asString()).isEqualTo(409);
        assertThat(riskant.path("fehlercode").toString()).isEqualTo("BESTAETIGUNG_NOETIG");
        Response bestaetigt = given().auth().oauth2(inhaber).header("If-Match", etag)
                .queryParam("bestaetigt", "chat.aufbewahrungMonate").contentType("application/merge-patch+json")
                .body(ungültigUndRiskant).patch(PFAD);
        assertThat(bestaetigt.statusCode()).as(bestaetigt.asString()).isEqualTo(401);
        assertThat(bestaetigt.path("fehlercode").toString()).isEqualTo("STEP_UP_NOETIG");
        Response ungueltigerName = patch(inhaber, etag, "{\"branding\":{\"name\":42}}");
        assertThat(ungueltigerName.statusCode()).as(ungueltigerName.asString()).isEqualTo(422);
        assertThat(given().auth().oauth2(inhaber).get("/api/v1/konfiguration").getHeader("ETag"))
                .isEqualTo(etag);
    }

    @Test
    void vorschauAenderungUndRuecksetzungBeruehrenKeinenAnderenMandanten() throws Exception {
        for (String code : new String[] {"urinet", "vhf-gsk"}) {
            if (!imRegister(code)) {
                provisionierung.anlegen(code, null, null);
            }
        }
        String urinet = Testanmeldung.token("inhaber-urinet");
        String vhf = Testanmeldung.token("inhaber-vhf-gsk");
        Response vorher = given().auth().oauth2(urinet).get("/api/v1/konfiguration");
        Response andererMandant = given().auth().oauth2(vhf).get("/api/v1/konfiguration");
        assertThat(vorher.statusCode()).as(vorher.asString()).isEqualTo(200);
        assertThat(andererMandant.statusCode()).as(andererMandant.asString()).isEqualTo(200);
        String bisher = vorher.jsonPath().getString("einstellungen.branding.name");
        String neu = "Testarchiv".equals(bisher) ? "Probe Archiv" : "Testarchiv";
        String body = "{\"branding\":{\"name\":\"" + neu + "\"}}";
        Response vorschau = given().auth().oauth2(urinet).header("If-Match", vorher.getHeader("ETag"))
                .contentType("application/merge-patch+json").body(body).post(PFAD + "/vorschau");
        assertThat(vorschau.statusCode()).as(vorschau.asString()).isEqualTo(200);
        assertThat(given().auth().oauth2(urinet).get("/api/v1/konfiguration").getHeader("ETag"))
                .isEqualTo(vorher.getHeader("ETag"));
        Response gespeichert = patch(urinet, vorher.getHeader("ETag"), body);
        assertThat(gespeichert.statusCode()).as(gespeichert.asString()).isEqualTo(200);
        try {
            assertThat(gespeichert.getHeader("ETag")).isNotEqualTo(vorher.getHeader("ETag"));
            assertThat(given().auth().oauth2(urinet).get("/api/v1/konfiguration")
                    .jsonPath().getString("einstellungen.branding.name")).isEqualTo(neu);
            Response vhfNachher = given().auth().oauth2(vhf).get("/api/v1/konfiguration");
            assertThat(vhfNachher.getHeader("ETag")).isEqualTo(andererMandant.getHeader("ETag"));
            assertThat(vhfNachher.asString()).isEqualTo(andererMandant.asString());
        } finally {
            Response zurueck = given().auth().oauth2(urinet).header("If-Match", gespeichert.getHeader("ETag"))
                    .contentType("application/json")
                    .body("{\"version\":" + vorher.jsonPath().getInt("basisVersion") + "}")
                    .post(PFAD + "/zuruecksetzen");
            assertThat(zurueck.statusCode()).as(zurueck.asString()).isEqualTo(200);
        }
        assertThat(given().auth().oauth2(urinet).get("/api/v1/konfiguration")
                .jsonPath().getString("einstellungen.branding.name")).isEqualTo(bisher);
    }

    private static Response patch(String token, String etag, String body) {
        var anfrage = given().auth().oauth2(token).contentType("application/merge-patch+json").body(body);
        if (etag != null) {
            anfrage.header("If-Match", etag);
        }
        return anfrage.patch(PFAD);
    }

    private boolean imRegister(String code) throws Exception {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
