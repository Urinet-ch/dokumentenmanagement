package ch.urinet.archiv.mandant;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Worker migriert nie, weder {@code db/public} noch Mandanten-Schemas (design.md «Mandant»): Mit Eigentümer-Zugang,
 * den es nicht gibt, startet er trotzdem, weil er keine Verbindung als Eigentümer öffnet.
 */
@QuarkusTest
@TestProfile(MigrationWorkerTest.NurWorker.class)
class MigrationWorkerTest {

    public static class NurWorker implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "archiv.rolle", "worker",
                    "quarkus.liquibase.username", "kein_eigentuemer",
                    "quarkus.liquibase.password", "falsch",
                    // unabhängig davon, ob ein anderer Test db/public im Container schon migriert hat
                    "quarkus.hibernate-orm.schema-management.strategy", "none");
        }
    }

    @Test
    void workerMigriertNicht() {
        assertThat(ConfigProvider.getConfig().getValue("quarkus.liquibase.migrate-at-start", Boolean.class)).isFalse();
    }
}
