package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zu Req 2.1 und 2.2: Anmeldung über Keycloak mit TOTP, Rollen und Mandant im Token. */
public class AnmeldungSchritte {

    private static final Path REALM_EXPORT = Path.of("../deploy/keycloak/realm-export.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private Response anmeldung;
    private String token;
    private Response antwort;

    @Wenn("sich «{word}» nur mit dem Passwort anmeldet")
    public void nurPasswort(String benutzer) {
        anmeldung = Testanmeldung.anmelden(benutzer, false);
    }

    @Dann("stellt Keycloak kein Token aus")
    public void keinToken() {
        assertThat(anmeldung.statusCode()).isEqualTo(400);
        assertThat(anmeldung.body().asString()).doesNotContain("access_token");
    }

    @Wenn("sich «{word}» mit Passwort und TOTP anmeldet")
    public void mitTotp(String benutzer) {
        token = Testanmeldung.token(benutzer);
    }

    @Wenn("sich «{word}» über den Client «{word}» mit Passwort und TOTP anmeldet")
    public void mitTotpUeberClient(String benutzer, String client) {
        token = Testanmeldung.token(client, benutzer);
    }

    @Und("mit diesem Token «{word}» abfragt")
    public void mitTokenAbfragen(String pfad) {
        antwort = given().auth().oauth2(token).when().get(pfad);
    }

    @Wenn("jemand ohne Token «{word}» abfragt")
    public void ohneToken(String pfad) {
        antwort = given().when().get(pfad);
    }

    @Dann("lehnt das Archiv die Anfrage mit Status 401 ab")
    public void abgelehnt() {
        assertThat(antwort.statusCode()).isEqualTo(401);
    }

    @Dann("antwortet das Archiv angemeldet mit Status {int}")
    public void angemeldet(int status) {
        assertThat(antwort.statusCode()).isEqualTo(status);
    }

    @Dann("enthält das Token die Rolle «{word}» und den Mandanten «{word}»")
    public void rolleUndMandant(String rolle, String mandant) throws IOException {
        JsonNode inhalt = JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        List<String> rollen = new ArrayList<>();
        inhalt.path("realm_access").path("roles").forEach(r -> rollen.add(r.asText()));
        assertThat(rollen).contains(rolle);
        assertThat(rollen).doesNotContain(andereRollen(rolle).toArray(String[]::new));
        assertThat(inhalt.path("mandant").asText()).isEqualTo(mandant);
    }

    @Dann("kennt der Realm-Export genau die Rollen «{word}», «{word}», «{word}» und «{word}»")
    public void rollenImExport(String a, String b, String c, String d) throws IOException {
        List<String> rollen = new ArrayList<>();
        export().path("roles").path("realm").forEach(r -> rollen.add(r.path("name").asText()));
        rollen.removeIf(r -> r.equals("offline_access") || r.equals("uma_authorization") || r.startsWith("default-roles-"));
        assertThat(rollen).containsExactlyInAnyOrder(a, b, c, d);
    }

    @Dann("verlangt der Realm-Export im Browser Passwort und TOTP")
    public void totpImBrowser() throws IOException {
        JsonNode realm = export();
        String flow = realm.path("browserFlow").asText();
        List<String> verlangt = new ArrayList<>();
        for (JsonNode f : realm.path("authenticationFlows")) {
            if (f.path("alias").asText().startsWith(flow)) {
                f.path("authenticationExecutions").forEach(e -> {
                    if ("REQUIRED".equals(e.path("requirement").asText())) {
                        verlangt.add(e.path("authenticator").asText());
                    }
                });
            }
        }
        assertThat(verlangt).contains("auth-username-password-form", "auth-otp-form");
        assertThat(realm.path("otpPolicyType").asText()).isEqualTo("totp");
    }

    @Und("jeder neue Benutzer muss TOTP einrichten")
    public void totpFuerNeue() throws IOException {
        boolean pflicht = false;
        for (JsonNode aktion : export().path("requiredActions")) {
            if ("CONFIGURE_TOTP".equals(aktion.path("alias").asText())) {
                pflicht = aktion.path("enabled").asBoolean() && aktion.path("defaultAction").asBoolean();
            }
        }
        assertThat(pflicht).isTrue();
    }

    private static List<String> andereRollen(String rolle) {
        List<String> alle = new ArrayList<>(List.of("inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"));
        alle.remove(rolle);
        return alle;
    }

    private static JsonNode export() throws IOException {
        return JSON.readTree(Files.readString(REALM_EXPORT, StandardCharsets.UTF_8));
    }
}
