package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.ArchivMain;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("start-3")
@QuarkusMainTest
@TestProfile(StepUpUngueltigStartTest.Einstellung.class)
class StepUpUngueltigStartTest {

    public static class Einstellung implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("archiv.verwaltung.step-up-gueltigkeit", "PT16M");
        }
    }

    @AfterAll
    static void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
    }

    @Test
    @Launch(value = {"token", "erstellen", "probe-gibts-nicht", "Probe", "import"}, exitCode = 3)
    void startBrichtBeiUngueltigerGueltigkeitAb(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput())
                .contains("Start fehlgeschlagen", "archiv.verwaltung.step-up-gueltigkeit");
        assertThat(ergebnis.getOutput()).doesNotContain("ua_");
    }
}
