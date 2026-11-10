package ch.urinet.archiv.gemeinsam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Keycloak nicht erreichbar (z. B. nach einem Deploy, solange {@code kc} noch startet): Die API antwortet 401 statt 500
 * (Aufgabe 1.9, Review PR #37); die Liveness bleibt UP, damit der Container nicht neu startet, die Readiness meldet
 * Keycloak DOWN ({@code quarkus.oidc.health.enabled}).
 */
@QuarkusTest
@TestProfile(OidcNichtErreichbarTest.OhneKeycloak.class)
class OidcNichtErreichbarTest {

    public static class OhneKeycloak implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.keycloak.devservices.enabled", "false",
                    "quarkus.oidc.auth-server-url", "http://127.0.0.1:1/realms/urinet-archiv");
        }
    }

    @Test
    void apiMitJwtAntwortet401() {
        given().header("Authorization", "Bearer " + jwt())
                .when().get("/api/v1/api-tokens")
                .then().statusCode(401)
                .header("WWW-Authenticate", containsString("Bearer"));
    }

    @Test
    void livenessBleibtUp() {
        given().when().get("/q/health/live").then().statusCode(200);
    }

    @Test
    void readinessMeldetKeycloakDown() {
        given().when().get("/q/health/ready").then().statusCode(503)
                .body(containsString("OIDC Provider Health Check"));
    }

    /** Formal gültiges, unsigniertes JWT; geprüft werden kann es ohne Keycloak ohnehin nicht. */
    private static String jwt() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String kopf = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        String inhalt = b64.encodeToString(
                "{\"sub\":\"x\",\"aud\":\"archiv-api\",\"exp\":4102444800}".getBytes(StandardCharsets.UTF_8));
        return kopf + "." + inhalt + "." + b64.encodeToString(new byte[32]);
    }
}
