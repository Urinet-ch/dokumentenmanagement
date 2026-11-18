package ch.urinet.archiv.verarbeitung;

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

/** Eine Sicherungsfrist von null bricht den Start ab (Aufgabe 5.6). */
@Tag("start-4")
@QuarkusMainTest
@TestProfile(SicherungFristNullStartTest.Einstellung.class)
class SicherungFristNullStartTest {

    public static class Einstellung implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("archiv.ueberwachung.sicherung-frist", "0s");
        }
    }

    @AfterAll
    static void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
    }

    @Test
    @Launch(value = {"token", "erstellen", "probe-gibts-nicht", "Probe", "import"}, exitCode = 3)
    void startBrichtAb(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains("Start fehlgeschlagen",
                "archiv.ueberwachung.sicherung-frist");
        assertThat(ergebnis.getOutput()).as("kein Token ausgegeben").doesNotContain("ua_");
    }
}
