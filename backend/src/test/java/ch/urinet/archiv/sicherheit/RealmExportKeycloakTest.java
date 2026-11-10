package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Req 2.1 gegen den in Keycloak importierten Realm-Export (Stufe A und B): ohne TOTP kein Token über irgendeinen
 * Client oder Ablauf, und die API nimmt nur Tokens mit Audience {@code archiv-api} an. Gegenprobe:
 * {@code mvn verify -Dtest=RealmExportKeycloakTest -Darchiv.realm-export=<alter Export>} muss rot werden.
 */
@QuarkusTest
@TestProfile(RealmExportKeycloakTest.MitExport.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealmExportKeycloakTest {

    public static class MitExport implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.keycloak.devservices.enabled", "false");
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(ExportKeycloak.class));
        }
    }

    private static final String REALM = "/admin/realms/urinet-archiv";
    private static final String PASSWORT = "Probe-Passwort-1";
    // Pfad unter /api/, den es nie geben wird: angemeldet ergibt immer 404
    private static final String PROBE_PFAD = "/api/v1/anmeldung-probe";

    private String admin;

    @BeforeAll
    void vorbereiten() {
        admin = given().formParam("grant_type", "password").formParam("client_id", "admin-cli")
                .formParam("username", ExportKeycloak.ADMIN).formParam("password", ExportKeycloak.ADMIN_PASSWORT)
                .post(ExportKeycloak.basisUrl + "/realms/master/protocol/openid-connect/token").path("access_token");
        // Benutzer und Probe-Clients entstehen erst nach dem Import, wie es ein Administrator täte
        importieren(Map.of("ifResourceExists", "SKIP",
                "users", List.of(benutzer("ohne-totp", false), benutzer("mit-totp-1", true), benutzer("mit-totp-2", true)),
                "clients", List.of(probeClient("probe-fremd", false), probeClient("probe-mit-audience", true))));
        // Ohne offene Pflichtaktion: geprüft wird die Flow-Bindung, nicht CONFIGURE_TOTP als Standardaktion
        String id = adminGet(REALM + "/users?exact=true&username=ohne-totp").path("[0].id");
        given().auth().oauth2(admin).contentType(ContentType.JSON).body(Map.of("requiredActions", List.of()))
                .put(ExportKeycloak.basisUrl + REALM + "/users/" + id).then().statusCode(204);
    }

    @Test
    @Order(1)
    void positivkontrolleMitEingebautemDirectGrant() {
        String eingebaut = null;
        for (Map<String, Object> flow : adminGet(REALM + "/authentication/flows").jsonPath().<Map<String, Object>>getList("$")) {
            if ("direct grant".equals(flow.get("alias"))) {
                eingebaut = (String) flow.get("id");
            }
        }
        assertThat(eingebaut).as("eingebauter Ablauf «direct grant»").isNotNull();
        Map<String, Object> client = new HashMap<>(probeClient("probe-override", false));
        client.put("authenticationFlowBindingOverrides", Map.of("direct_grant", eingebaut));
        importieren(Map.of("ifResourceExists", "SKIP", "clients", List.of(client)));
        try {
            // Der Test erkennt ein Token, wo es eines gibt, und die Prüfung je Client schlägt an
            Response antwort = Testanmeldung.anmelden(realmUrl(), "probe-override", "ohne-totp", PASSWORT, null);
            assertThat(antwort.statusCode()).isEqualTo(200);
            assertThat(ungeschuetzteClients()).containsExactly("probe-override");
        } finally {
            loeschen("/clients/" + clientId("probe-override"));
        }
    }

    @Test
    @Order(2)
    void jedeFlowBindungVerlangtTotpOhneBedingung() {
        Map<String, Object> realm = adminGet(REALM).as(Map.class);
        for (String bindung : List.of("browserFlow", "directGrantFlow", "resetCredentialsFlow")) {
            String alias = (String) realm.get(bindung);
            assertThat(OtpPruefung.otpOhneBedingung(schritte(alias))).as(bindung + " = " + alias).isTrue();
        }
    }

    @Test
    @Order(2)
    void keinClientUeberschreibtDieFlowBindung() {
        assertThat(ungeschuetzteClients()).isEmpty();
    }

    @Test
    @Order(2)
    void keineIdentityProvider() {
        assertThat(identityProvider()).isEmpty();
        // Gegenprobe: ein angelegter IdP wird erkannt
        given().auth().oauth2(admin).contentType(ContentType.JSON)
                .body(Map.of("alias", "probe-idp", "providerId", "oidc", "enabled", true,
                        "config", Map.of("authorizationUrl", "https://idp.example.invalid/auth",
                                "tokenUrl", "https://idp.example.invalid/token", "clientId", "probe",
                                "clientAuthMethod", "client_secret_post")))
                .post(ExportKeycloak.basisUrl + REALM + "/identity-provider/instances").then().statusCode(201);
        try {
            assertThat(identityProvider()).containsExactly("probe-idp");
        } finally {
            loeschen("/identity-provider/instances/probe-idp");
        }
        assertThat(identityProvider()).isEmpty();
    }

    @Test
    @Order(3)
    void ohneTotpKeinTokenUeberIrgendeinenClient() {
        for (Map<String, Object> client : adminGet(REALM + "/clients").jsonPath().<Map<String, Object>>getList("$")) {
            String clientId = (String) client.get("clientId");
            Response antwort = Testanmeldung.anmelden(realmUrl(), clientId, "ohne-totp", PASSWORT, null);
            assertThat(antwort.statusCode()).as(clientId).isNotEqualTo(200);
            assertThat(antwort.body().asString()).as(clientId).doesNotContain("access_token");
        }
    }

    @Test
    @Order(3)
    void mitTotpAberOhneCodeKeinToken() {
        Response antwort = Testanmeldung.anmelden(realmUrl(), "probe-mit-audience", "mit-totp-1", PASSWORT, null);
        assertThat(antwort.statusCode()).isNotEqualTo(200);
        assertThat(antwort.body().asString()).doesNotContain("access_token");
    }

    @Test
    @Order(3)
    void apiNimmtNurTokensMitAudienceArchivApi() {
        String fremd = Testanmeldung.anmelden(realmUrl(), "probe-fremd", "mit-totp-1", PASSWORT,
                Testanmeldung.OTP_SECRET).path("access_token");
        String eigen = Testanmeldung.anmelden(realmUrl(), "probe-mit-audience", "mit-totp-2", PASSWORT,
                Testanmeldung.OTP_SECRET).path("access_token");
        assertThat(fremd).isNotNull();
        assertThat(eigen).isNotNull();
        given().auth().oauth2(fremd).get(PROBE_PFAD).then().statusCode(401);
        given().auth().oauth2(eigen).get(PROBE_PFAD).then().statusCode(404);
    }

    /** Clients, deren eigene Flow-Bindung keinen OTP-Schritt ohne Bedingung verlangt. */
    private List<String> ungeschuetzteClients() {
        List<String> ungeschuetzt = new ArrayList<>();
        for (Map<String, Object> client : adminGet(REALM + "/clients").jsonPath().<Map<String, Object>>getList("$")) {
            Map<String, String> bindungen = (Map<String, String>) client.getOrDefault("authenticationFlowBindingOverrides", Map.of());
            for (String flowId : bindungen.values()) {
                String alias = adminGet(REALM + "/authentication/flows/{id}", flowId).path("alias");
                if (!OtpPruefung.otpOhneBedingung(schritte(alias))) {
                    ungeschuetzt.add((String) client.get("clientId"));
                }
            }
        }
        return ungeschuetzt;
    }

    private List<Map<String, Object>> schritte(String alias) {
        return adminGet(REALM + "/authentication/flows/{alias}/executions", alias).jsonPath().getList("$");
    }

    private List<String> identityProvider() {
        return adminGet(REALM + "/identity-provider/instances").jsonPath().getList("alias");
    }

    private String clientId(String clientId) {
        return adminGet(REALM + "/clients?clientId={c}", clientId).path("[0].id");
    }

    private void loeschen(String pfad) {
        given().auth().oauth2(admin).delete(ExportKeycloak.basisUrl + REALM + pfad).then().statusCode(204);
    }

    private static Map<String, Object> benutzer(String name, boolean mitTotp) {
        List<Map<String, Object>> zugang = mitTotp
                ? List.of(passwort(), Map.of("type", "otp", "userLabel", "Probe",
                        "secretData", "{\"value\":\"" + Testanmeldung.OTP_SECRET + "\"}",
                        "credentialData", "{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,\"algorithm\":\"HmacSHA1\"}"))
                : List.of(passwort());
        return Map.of("username", name, "enabled", true, "email", name + "@example.invalid", "emailVerified", true,
                "firstName", "Probe", "lastName", name, "attributes", Map.of("mandant", List.of("urinet")),
                "requiredActions", List.of(), "credentials", zugang);
    }

    private static Map<String, Object> passwort() {
        return Map.of("type", "password", "value", PASSWORT, "temporary", false);
    }

    /** Ein Client mit Direct Grant, wie ihn ein Administrator nachträglich anlegen könnte. */
    private static Map<String, Object> probeClient(String clientId, boolean mitAudience) {
        List<Map<String, Object>> mapper = mitAudience
                ? List.of(Map.of("name", "audience-archiv-api", "protocol", "openid-connect",
                        "protocolMapper", "oidc-audience-mapper",
                        "config", Map.of("included.client.audience", "archiv-api", "access.token.claim", "true")))
                : List.of();
        return Map.of("clientId", clientId, "enabled", true, "publicClient", true, "standardFlowEnabled", false,
                "directAccessGrantsEnabled", true, "protocolMappers", mapper);
    }

    private void importieren(Map<String, Object> inhalt) {
        given().auth().oauth2(admin).contentType(ContentType.JSON).body(inhalt)
                .post(ExportKeycloak.basisUrl + REALM + "/partialImport").then().statusCode(200);
    }

    private Response adminGet(String pfad, Object... parameter) {
        Response antwort = given().auth().oauth2(admin).get(ExportKeycloak.basisUrl + pfad, parameter);
        assertThat(antwort.statusCode()).as(pfad).isEqualTo(200);
        return antwort;
    }

    private static String realmUrl() {
        return ExportKeycloak.basisUrl + "/realms/urinet-archiv";
    }
}
