package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayDeque;
import java.util.Deque;
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
    private static final List<String> OTP_SCHRITTE = List.of("auth-otp-form", "direct-grant-validate-otp");

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
    void jedeFlowBindungVerlangtTotpOhneBedingung() {
        Map<String, Object> realm = adminGet(REALM).as(Map.class);
        for (String bindung : List.of("browserFlow", "directGrantFlow", "resetCredentialsFlow")) {
            String alias = (String) realm.get(bindung);
            List<Map<String, Object>> schritte = adminGet(REALM + "/authentication/flows/{alias}/executions", alias)
                    .jsonPath().getList("$");
            assertThat(otpOhneBedingung(schritte)).as(bindung + " = " + alias).isTrue();
        }
        assertThat((List<?>) realm.getOrDefault("identityProviders", List.of())).as("keine Identity Provider").isEmpty();
    }

    @Test
    void ohneTotpKeinTokenUeberIrgendeinenClient() {
        for (Map<String, Object> client : adminGet(REALM + "/clients").jsonPath().<Map<String, Object>>getList("$")) {
            String clientId = (String) client.get("clientId");
            Response antwort = Testanmeldung.anmelden(realmUrl(), clientId, "ohne-totp", PASSWORT, null);
            assertThat(antwort.statusCode()).as(clientId).isNotEqualTo(200);
            assertThat(antwort.body().asString()).as(clientId).doesNotContain("access_token");
        }
    }

    @Test
    void mitTotpAberOhneCodeKeinToken() {
        Response antwort = Testanmeldung.anmelden(realmUrl(), "probe-mit-audience", "mit-totp-1", PASSWORT, null);
        assertThat(antwort.statusCode()).isNotEqualTo(200);
        assertThat(antwort.body().asString()).doesNotContain("access_token");
    }

    @Test
    void apiNimmtNurTokensMitAudienceArchivApi() {
        String fremd = Testanmeldung.anmelden(realmUrl(), "probe-fremd", "mit-totp-1", PASSWORT,
                Testanmeldung.OTP_SECRET).path("access_token");
        String eigen = Testanmeldung.anmelden(realmUrl(), "probe-mit-audience", "mit-totp-2", PASSWORT,
                Testanmeldung.OTP_SECRET).path("access_token");
        assertThat(fremd).isNotNull();
        assertThat(eigen).isNotNull();
        given().auth().oauth2(fremd).get("/api/v1/dokumente").then().statusCode(401);
        // Gegenkontrolle: angemeldet; den Pfad gibt es noch nicht
        given().auth().oauth2(eigen).get("/api/v1/dokumente").then().statusCode(404);
    }

    /** OTP-Schritt ist REQUIRED, und kein übergeordneter Ablauf ist CONDITIONAL oder DISABLED. */
    static boolean otpOhneBedingung(List<Map<String, Object>> schritte) {
        Deque<String> vorfahren = new ArrayDeque<>();
        for (Map<String, Object> schritt : schritte) {
            int ebene = ((Number) schritt.get("level")).intValue();
            while (vorfahren.size() > ebene) {
                vorfahren.pop();
            }
            String anforderung = (String) schritt.get("requirement");
            if (Boolean.TRUE.equals(schritt.get("authenticationFlow"))) {
                vorfahren.push(anforderung);
                continue;
            }
            if (OTP_SCHRITTE.contains(schritt.get("providerId")) && "REQUIRED".equals(anforderung)
                    && vorfahren.stream().noneMatch(a -> a.equals("CONDITIONAL") || a.equals("DISABLED"))) {
                return true;
            }
        }
        return false;
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
