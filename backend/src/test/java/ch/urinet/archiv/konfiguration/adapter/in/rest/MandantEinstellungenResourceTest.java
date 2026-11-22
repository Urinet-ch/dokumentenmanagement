package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class MandantEinstellungenResourceTest {
    private static final String PFAD = "/api/v1/mandant/einstellungen";

    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenbank;

    @Test
    void katalogUndBetriebsbudgetSindMandantenbezogenUndNurFuerBerechtigteLesbar() throws Exception {
        for (String code : new String[] {"urinet", "vhf-gsk"}) {
            if (!imRegister(code)) {
                provisionierung.anlegen(code, null, null);
            }
        }
        Response urinet = given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).get(PFAD);
        Response vhf = given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk")).get(PFAD);
        assertThat(urinet.statusCode()).as(urinet.asString()).isEqualTo(200);
        assertThat(vhf.statusCode()).as(vhf.asString()).isEqualTo(200);
        assertThat(urinet.getHeader("ETag")).isEqualTo("\"" + urinet.jsonPath().getInt("version") + "\"");
        assertThat(urinet.jsonPath().getList("katalog")).hasSize(12);
        assertThat(urinet.jsonPath().getString("katalog.find { it.schluessel == 'branding.name' }.standard"))
                .isEqualTo("UriNet Archiv");
        assertThat(vhf.jsonPath().getString("katalog.find { it.schluessel == 'branding.name' }.standard"))
                .isEqualTo("VHF/GSK Archiv");
        assertThat(urinet.jsonPath().getLong("betrieb.oeffentlicherChat.budgetTokens")).isEqualTo(2_000_000L);
        assertThat(urinet.jsonPath().getLong("betrieb.wissenMonatsbudgetTokens")).isEqualTo(1_000_000L);
        assertThat(urinet.asString()).doesNotContain("alarmEmpfaenger", "secret", "passwort");
        assertThat(given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet")).get(PFAD).statusCode())
                .isEqualTo(403);
    }

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

        Response anonym = given().contentType("application/merge-patch+json").body(ungültigUndRiskant).patch(PFAD);
        assertThat(anonym.statusCode()).isEqualTo(401);
        assertThat(anonym.getHeader("ETag")).isNull();
        Response verboten = given().auth().oauth2(mitarbeiter).contentType("application/merge-patch+json")
                .body(ungültigUndRiskant).patch(PFAD);
        assertThat(verboten.statusCode()).isEqualTo(403);
        assertThat(verboten.getHeader("ETag")).isNull();
        Response fehlendeVersion = patch(inhaber, null, "{");
        assertThat(fehlendeVersion.statusCode()).isEqualTo(428);
        assertThat(fehlendeVersion.getHeader("ETag")).isEqualTo(etag);
        Response veralteteVersion = patch(inhaber, "\"0\"", ungültigUndRiskant);
        assertThat(veralteteVersion.statusCode()).isEqualTo(412);
        assertThat(veralteteVersion.getHeader("ETag")).isEqualTo(etag);
        Response riskant = patch(inhaber, etag, ungültigUndRiskant);
        assertThat(riskant.statusCode()).as(riskant.asString()).isEqualTo(409);
        assertThat(riskant.path("fehlercode").toString()).isEqualTo("BESTAETIGUNG_NOETIG");
        assertThat(riskant.getHeader("ETag")).isEqualTo(etag);
        Response bestaetigt = given().auth().oauth2(inhaber).header("If-Match", etag)
                .queryParam("bestaetigt", "chat.aufbewahrungMonate").contentType("application/merge-patch+json")
                .body(ungültigUndRiskant).patch(PFAD);
        assertThat(bestaetigt.statusCode()).as(bestaetigt.asString()).isEqualTo(401);
        assertThat(bestaetigt.path("fehlercode").toString()).isEqualTo("STEP_UP_NOETIG");
        assertThat(bestaetigt.getHeader("ETag")).isEqualTo(etag);
        Response ungueltigerName = patch(inhaber, etag, "{\"branding\":{\"name\":42}}");
        assertThat(ungueltigerName.statusCode()).as(ungueltigerName.asString()).isEqualTo(422);
        assertThat(ungueltigerName.getHeader("ETag")).isEqualTo(etag);
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

    @Test
    void chunkedAnfragenWerdenBeimEmpfangBegrenzt() throws Exception {
        if (!imRegister("urinet")) {
            provisionierung.anlegen("urinet", null, null);
        }
        String token = Testanmeldung.token("inhaber-urinet");
        for (String[] anfrage : new String[][] {
                {"PATCH", PFAD}, {"PATCH", "/api/v1/mandant/%65instellungen/"},
                {"POST", PFAD + "/vorschau"}, {"POST", PFAD + "/zuruecksetzen"}
        }) {
            try (Socket socket = new Socket("127.0.0.1", RestAssured.port)) {
                socket.setSoTimeout(10_000);
                String kopf = anfrage[0] + " " + anfrage[1] + " HTTP/1.1\r\nHost: localhost\r\n"
                        + "Authorization: Bearer " + token + "\r\n"
                        + "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
                var aus = socket.getOutputStream();
                aus.write(kopf.getBytes(StandardCharsets.US_ASCII));
                aus.write("4001\r\n".getBytes(StandardCharsets.US_ASCII));
                aus.write(" ".repeat(16 * 1024 + 1).getBytes(StandardCharsets.US_ASCII));
                aus.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                aus.flush();
                BufferedReader leser = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                assertThat(leser.readLine()).as(anfrage[1]).contains(" 413 ");
            }
        }
        String etag = given().auth().oauth2(token).get("/api/v1/konfiguration").getHeader("ETag");
        HttpURLConnection verbindung = (HttpURLConnection) URI.create("http://127.0.0.1:" + RestAssured.port
                + PFAD + "/vorschau").toURL().openConnection();
        verbindung.setRequestMethod("POST");
        verbindung.setDoOutput(true);
        verbindung.setChunkedStreamingMode(1024);
        verbindung.setRequestProperty("Authorization", "Bearer " + token);
        verbindung.setRequestProperty("Content-Type", "application/json");
        verbindung.setRequestProperty("If-Match", etag);
        try (var aus = verbindung.getOutputStream()) {
            aus.write(("{}" + " ".repeat(16 * 1024 - 2)).getBytes(StandardCharsets.US_ASCII));
        }
        assertThat(verbindung.getResponseCode()).isEqualTo(200);
        verbindung.disconnect();
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
