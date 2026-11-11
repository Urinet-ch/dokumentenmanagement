package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.gemeinsam.adapter.in.health.OidcBereitschaftWaechter;
import ch.urinet.archiv.gemeinsam.domain.Bereitschaftsfrist.Lage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * Keycloak läuft, aber ohne den konfigurierten Realm (falsch konfiguriert, Aufgabe 1.9, entschieden von Fabian am
 * 11.11.2026, PR #42 P42-1): Die Readiness meldet DOWN, damit der Deploy-Workflow zurückrollt; im Betrieb beendet sich
 * die API nach der Frist mit Exit-Code 75.
 */
@QuarkusTest
@TestProfile(OidcFalschKonfiguriertTest.FalscherRealm.class)
class OidcFalschKonfiguriertTest {

    @Inject
    OidcBereitschaftWaechter waechter;

    public static class FalscherRealm implements QuarkusTestProfile {
        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(FakeKeycloak.class, Map.of("realm", "/realms/anderer-realm")));
        }
    }

    @BeforeEach
    void ende() {
        OidcNichtErreichbarTest.prozessEndeMerken();
        OidcNichtErreichbarTest.EXIT_CODES.clear();
    }

    @Test
    void readinessDown() {
        given().when().get("/q/health/ready").then().statusCode(503)
                .body(containsString("OIDC"));
    }

    @Test
    void waechterBeendetNachDerFrist() {
        long start = System.nanoTime();
        assertThat(waechter.pruefen(start)).contains(Lage.WARTEN);
        assertThat(waechter.pruefen(start + Duration.ofMinutes(5).toNanos())).contains(Lage.BEENDEN);
        assertThat(OidcNichtErreichbarTest.EXIT_CODES).containsExactly(OidcBereitschaftWaechter.EXIT_CODE);
    }
}
