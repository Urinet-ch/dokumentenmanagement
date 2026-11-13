package ch.urinet.archiv.mandant;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.containsString;

/**
 * Geplante Jobs greifen erst nach {@code MigrationBeimStart} auf Mandanten-Schemas zu (design.md «Mandant», Abschnitt
 * «Start und Migration»): Der Start hält vor der Migration an ({@link VorMigrationBeobachter}), während der Takt
 * {@code kennzahlen} schon jede Sekunde auslöst und das Schema des Mandanten {@code probe-vor-migration} noch leer ist.
 * Bis zur Migration überspringt der Scheduler den Takt ohne Warnung im Log, und {@code /q/health/ready} ist noch nicht
 * erreichbar (der HTTP-Server öffnet erst nach den Start-Beobachtern); danach läuft der Takt normal. Der Nachhollauf der
 * Manifeste beim Start (Req 15.6, {@code archiv.manifest.beim-start}, Rolle {@code worker}) läuft genau einmal, erst
 * nach dem Signal ({@link ManifestPlanenAttrappe}; Review PR #113). DOWN vor dem Signal zeigt
 * {@code SchemaBereitschaftCheckTest}.
 */
@Tag("start-2")
@QuarkusTest
@TestProfile(JobsVorMigrationTest.TaktVorMigration.class)
class JobsVorMigrationTest {

    public static class TaktVorMigration implements QuarkusTestProfile {

        /** fester freier Port, damit die Testhilfe die Readiness schon vor dem Öffnen des Sockets ansprechen kann */
        private static final int PORT = freierPort();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "archiv.test.start-mandant", "probe-vor-migration",
                    "archiv.test.vor-migration-warten", "3s",
                    "quarkus.http.test-port", String.valueOf(PORT),
                    // nur lesend, ohne Nebenwirkungen auf andere Tests
                    "archiv.kennzahlen.intervall", "1s",
                    // Rolle api,worker wie im Testprofil; planen läuft gegen die Attrappe, ohne Aufträge
                    "archiv.manifest.beim-start", "true");
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(ManifestPlanenAttrappe.class);
        }

        private static int freierPort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Inject
    VorMigrationBeobachter beobachter;

    @Inject
    ManifestPlanenAttrappe planen;

    @Test
    void taktWartetAufDieMigrationUndLaeuftDanach() throws InterruptedException {
        assertSoftly(vor -> {
            vor.assertThat(beobachter.uebersprungenVor()).contains("kennzahlen");
            vor.assertThat(beobachter.gelaufenVor()).isEmpty();
            vor.assertThat(beobachter.warnungenVor()).isEmpty();
            vor.assertThat(beobachter.readinessVorMigration()).as("Server noch zu").isEqualTo(-1);
        });
        // der Start meldet das Signal synchron in MigrationBeimStart, vor dem ersten Test
        assertThat(planen.aufrufe()).as("Nachhollauf nur nach dem Signal").containsExactly(true);

        long frist = System.nanoTime() + 10_000_000_000L;
        while (!beobachter.gelaufenNach().contains("kennzahlen") && System.nanoTime() < frist) {
            Thread.sleep(100);
        }
        assertThat(beobachter.gelaufenNach()).contains("kennzahlen");
        given().when().get("/q/health/ready").then().statusCode(200)
                .body(containsString("Mandanten-Schemas migriert"));
    }
}
