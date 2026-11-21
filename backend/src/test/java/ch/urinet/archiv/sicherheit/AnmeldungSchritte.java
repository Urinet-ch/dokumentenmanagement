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
    private static final Path REALM_TEST_EXPORT = Path.of("../deploy/keycloak/realm-test.json");
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
        assertThat(ProblemDetails.pruefen(antwort, 401)).isEqualTo("NICHT_ANGEMELDET");
        assertThat(antwort.header("WWW-Authenticate")).startsWith("Bearer");
    }

    @Dann("antwortet das Archiv angemeldet mit Status {int}")
    public void angemeldet(int status) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo("NICHT_GEFUNDEN");
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

    @Dann("beträgt die Access-Token-Lebensdauer des Realm-Exports {int} Sekunden")
    public void accessTokenLebensdauer(int sekunden) throws IOException {
        assertThat(export().path("accessTokenLifespan").asInt()).isEqualTo(sekunden);
    }

    @Dann("verlangt der Realm-Export Passwörter mit mindestens 12 Zeichen ohne Benutzername oder E-Mail")
    public void passwortRichtlinie() throws IOException {
        assertThat(export().path("passwordPolicy").asText()).isEqualTo("length(12) and notUsername and notEmail");
    }

    @Dann("ist Google im Realm-Export als deaktivierter, nicht vertrauender Provider vorbereitet")
    public void googleProviderVorbereitet() throws IOException {
        JsonNode google = export().path("identityProviders").path(0);
        assertThat(google.path("alias").asText()).isEqualTo("google");
        assertThat(google.path("enabled").asBoolean()).isFalse();
        assertThat(google.path("trustEmail").asBoolean()).isFalse();
        assertThat(google.path("storeToken").asBoolean()).isFalse();
        assertThat(google.path("mappers").isMissingNode()).isTrue();
    }

    @Dann("verweigert der Google-Erstlogin unbekannte Konten")
    public void googleErstloginVerweigertUnbekannte() throws IOException {
        JsonNode realm = export();
        assertThat(realm.path("identityProviders").path(0).path("firstBrokerLoginFlowAlias").asText())
                .isEqualTo("google-nur-verknuepft");
        JsonNode executions = flow(realm, "google-nur-verknuepft").path("authenticationExecutions");
        assertThat(executions.size()).isEqualTo(1);
        assertThat(executions.path(0).path("authenticator").asText()).isEqualTo("deny-access-authenticator");
        JsonNode oidcTest = provider(testExport(), "archiv-test-oidc");
        assertThat(oidcTest.path("providerId").asText()).isEqualTo("oidc");
    }

    @Dann("ist die Google-Verknüpfung nur administrativ möglich")
    public void googleVerknuepfungNurAdministrativ() throws IOException {
        JsonNode accountRoles = export().path("roles").path("client").path("account");
        JsonNode manageAccount = null;
        for (JsonNode role : accountRoles) {
            if ("manage-account".equals(role.path("name").asText())) {
                manageAccount = role;
            }
        }
        assertThat(manageAccount).isNotNull();
        assertThat(manageAccount.path("composites").size()).isZero();
        assertThat(export().path("adminEventsEnabled").asBoolean()).isTrue();
    }

    @Dann("verlangt die Google-Anmeldung weiterhin TOTP")
    public void googleTotpPflicht() throws IOException {
        JsonNode realm = export();
        assertThat(realm.path("identityProviders").path(0).path("postBrokerLoginFlowAlias").asText())
                .isEqualTo("google-totp-pflicht");
        JsonNode otp = flow(realm, "google-totp-pflicht").path("authenticationExecutions").path(0);
        assertThat(otp.path("authenticator").asText()).isEqualTo("auth-otp-form");
        assertThat(otp.path("requirement").asText()).isEqualTo("REQUIRED");
    }

    @Dann("bleibt das Google-Clientgeheimnis ein Platzhalter und die Anmeldeereignisse sind aktiviert")
    public void googleSecretUndEreignisse() throws IOException {
        JsonNode realm = export();
        assertThat(realm.path("identityProviders").path(0).path("config").path("clientId").asText())
                .isEqualTo("${GOOGLE_CLIENT_ID}");
        assertThat(realm.path("identityProviders").path(0).path("config").path("clientSecret").asText())
                .isEqualTo("${GOOGLE_CLIENT_SECRET}");
        assertThat(realm.path("eventsEnabled").asBoolean()).isTrue();
        assertThat(realm.path("enabledEventTypes"))
                .extracting(JsonNode::asText)
                .contains("LOGIN_ERROR", "IDENTITY_PROVIDER_LOGIN", "IDENTITY_PROVIDER_LOGIN_ERROR",
                        "IDENTITY_PROVIDER_FIRST_LOGIN_ERROR", "FEDERATED_IDENTITY_LINK",
                        "REMOVE_FEDERATED_IDENTITY");
    }

    private static JsonNode flow(JsonNode realm, String alias) {
        for (JsonNode flow : realm.path("authenticationFlows")) {
            if (alias.equals(flow.path("alias").asText())) {
                return flow;
            }
        }
        throw new AssertionError("Authentifizierungsablauf fehlt: " + alias);
    }

    private static JsonNode provider(JsonNode realm, String alias) {
        for (JsonNode provider : realm.path("identityProviders")) {
            if (alias.equals(provider.path("alias").asText())) {
                return provider;
            }
        }
        throw new AssertionError("Identity Provider fehlt: " + alias);
    }

    private static JsonNode testExport() throws IOException {
        return JSON.readTree(Files.readString(REALM_TEST_EXPORT, StandardCharsets.UTF_8));
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
