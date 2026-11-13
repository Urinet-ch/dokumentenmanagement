package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.gemeinsam.adapter.in.health.OidcBereitschaftWaechter;
import ch.urinet.archiv.gemeinsam.domain.Bereitschaftsfrist.Lage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * Keycloak erreichbar, schon bevor die API startet – der Normalfall nach einem Neustart durch den Wächter
 * (Aufgabe 1.9, entschieden von Fabian am 11.11.2026, PR #42 P42-1): Readiness UP samt OIDC-Tenant, der Wächter
 * beendet nichts, gültige Tokens gelten.
 */
@Tag("start-1")
@QuarkusTest
@TestProfile(OidcBereitschaftTest.MitKeycloak.class)
class OidcBereitschaftTest {

    @Inject
    OidcBereitschaftWaechter waechter;

    public static class MitKeycloak implements QuarkusTestProfile {
        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(FakeKeycloak.class));
        }
    }

    @BeforeEach
    void ende() {
        OidcNichtErreichbarTest.prozessEndeMerken();
        OidcNichtErreichbarTest.EXIT_CODES.clear();
    }

    @Test
    void readinessUpMitOidc() {
        given().when().get("/q/health/ready").then().statusCode(200)
                .body(containsString("OIDC"));
    }

    @Test
    void waechterBleibtRuhig() {
        long start = System.nanoTime();
        assertThat(waechter.pruefen(start)).contains(Lage.BEREIT);
        assertThat(waechter.pruefen(start + Duration.ofMinutes(10).toNanos())).contains(Lage.BEREIT);
        assertThat(OidcNichtErreichbarTest.EXIT_CODES).isEmpty();
    }

    @Test
    void gueltigesTokenGilt() throws Exception {
        String issuer = ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
        String token = FakeKeycloak.signiertesJwt(FakeKeycloak.schluesselDerRessource(), issuer);
        // angemeldet, aber ohne Rolle inhaber
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/v1/api-tokens").then().statusCode(403);
    }

}
