package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.gemeinsam.adapter.in.health.OidcBereitschaftWaechter;
import ch.urinet.archiv.gemeinsam.adapter.in.health.ProzessEnde;
import ch.urinet.archiv.gemeinsam.domain.Bereitschaftsfrist.Lage;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jose4j.jwk.RsaJsonWebKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * Keycloak beim Start nicht erreichbar (Aufgabe 1.9; Review PR #42 M1, entschieden von Fabian am 11.11.2026, P42-1):
 * z. B. nach einem Neustart der VPS, wenn Docker {@code api} vor {@code kc} startet ({@code depends_on} gilt dann
 * nicht). Die API antwortet 401 statt 500 (Review PR #37), Liveness bleibt UP, die Readiness meldet den OIDC-Tenant
 * DOWN. Ist er die Frist lang nicht bereit, beendet sich die API mit Exit-Code 75, damit Docker sie neu startet. Ohne
 * Neustart heilt erst eine angemeldete Anfrage den Tenant – die kommt hinter Traefik nicht, solange die API unhealthy
 * ist; darum der Wächter.
 */
@QuarkusTest
@TestProfile(OidcNichtErreichbarTest.OhneKeycloak.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OidcNichtErreichbarTest {

    static final List<Integer> EXIT_CODES = new CopyOnWriteArrayList<>();
    private static HttpServer keycloak;

    @Inject
    OidcBereitschaftWaechter waechter;

    /** Fester Port für den spät startenden Fake-Keycloak; über eine System-Property, weil Profil und Test in
     *  verschiedenen Classloadern laufen. */
    static int port() {
        return Integer.parseInt(System.getProperties().computeIfAbsent("archiv.test.oidc-port", k -> {
            try {
                return String.valueOf(FakeKeycloak.freierPort());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).toString());
    }

    public static class OhneKeycloak implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return FakeKeycloak.konfiguration(FakeKeycloak.issuer(port()));
        }
    }

    /** Ersetzt das Prozessende: der Test merkt sich den Exit-Code, statt die JVM zu beenden. */
    static void prozessEndeMerken() {
        QuarkusMock.installMockForType(new ProzessEnde() {
            @Override
            public void beenden(int code) {
                EXIT_CODES.add(code);
            }
        }, ProzessEnde.class);
    }

    @BeforeEach
    void ende() {
        prozessEndeMerken();
    }

    @AfterAll
    static void keycloakStoppen() {
        if (keycloak != null) {
            keycloak.stop(0);
        }
    }

    @Test
    @Order(1)
    void apiMitJwtAntwortet401() {
        given().header("Authorization", "Bearer " + FakeKeycloak.unsigniertesJwt())
                .when().get("/api/v1/api-tokens")
                .then().statusCode(401)
                .header("WWW-Authenticate", containsString("Bearer"));
    }

    @Test
    @Order(2)
    void livenessBleibtUp() {
        given().when().get("/q/health/live").then().statusCode(200);
    }

    @Test
    @Order(3)
    void readinessMeldetOidcDown() {
        given().when().get("/q/health/ready").then().statusCode(503)
                .body(containsString("OIDC"));
    }

    @Test
    @Order(4)
    void nachDerFristBeendetSichDieApiMitExitCode75() {
        long start = System.nanoTime();
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            assertThat(waechter.pruefen(start)).contains(Lage.WARTEN);
            assertThat(waechter.pruefen(start + Duration.ofSeconds(299).toNanos())).contains(Lage.WARTEN);
            assertThat(EXIT_CODES).isEmpty();

            assertThat(waechter.pruefen(start + Duration.ofMinutes(5).toNanos())).contains(Lage.BEENDEN);
            assertThat(EXIT_CODES).containsExactly(OidcBereitschaftWaechter.EXIT_CODE);

            String text = log.volltext();
            assertThat(text).containsPattern("Fehler [0-9a-f-]{36}: fehlercode=OIDC_NICHT_BEREIT")
                    .contains("Exit-Code 75")
                    .doesNotContain("127.0.0.1", "realms");
        }
        // einmal beendet, prüft der Wächter nicht mehr (kein zweiter Exit während des Herunterfahrens)
        assertThat(waechter.pruefen(start + Duration.ofMinutes(6).toNanos())).isEmpty();
        assertThat(EXIT_CODES).hasSize(1);
    }

    @Test
    @Order(5)
    void spaeterKeycloakHeiltErstMitAngemeldeterAnfrage() throws Exception {
        RsaJsonWebKey schluessel = FakeKeycloak.neuerSchluessel();
        String issuer = FakeKeycloak.issuer(port());
        keycloak = FakeKeycloak.starten(port(), FakeKeycloak.REALM, issuer, schluessel);

        // ohne angemeldete Anfrage baut Quarkus den Tenant nicht neu auf: ohne Neustart bliebe die API unhealthy
        given().when().get("/q/health/ready").then().statusCode(503);

        // angemeldet, aber ohne Rolle inhaber: 403 statt 401
        String token = FakeKeycloak.signiertesJwt(schluessel, issuer);
        Instant frist = Instant.now().plus(Duration.ofSeconds(30));
        int status;
        do {
            status = given().header("Authorization", "Bearer " + token)
                    .when().get("/api/v1/api-tokens").then().extract().statusCode();
            if (status == 403) {
                break;
            }
            Thread.sleep(500);
        } while (Instant.now().isBefore(frist));
        assertThat(status).as("Token nach spätem Start von Keycloak angenommen").isEqualTo(403);
        given().when().get("/q/health/ready").then().statusCode(200);
    }
}
