package ch.urinet.archiv.sicherheit;

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

/**
 * Eine ungültige Einstellung der {@code HashSperre} (negative Wartezeit) bricht den Start ab (Review PR #40, Muss 1), statt dass
 * die Anwendung startet und jede Token-Anfrage mit 500 endet. Über das Admin-Kommando wie {@code SecretDateiFehltTest},
 * weil der Dienst bei gelungenem Start nie endet; der unbekannte Mandant lässt einen trotzdem gelungenen Start mit
 * Exit 1 statt 3 enden, ohne etwas anzulegen.
 */
@Tag("start-1")
@QuarkusMainTest
@TestProfile(WartezeitNegativStartTest.Einstellung.class)
class WartezeitNegativStartTest {

    public static class Einstellung implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("archiv.api-token.wartezeit", "-1s");
        }
    }

    @AfterAll
    static void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
    }

    @Test
    @Launch(value = {"token", "erstellen", "probe-gibts-nicht", "Probe", "import"}, exitCode = 3)
    void startBrichtAb(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains("Start fehlgeschlagen", "archiv.api-token.wartezeit");
        assertThat(ergebnis.getOutput()).as("kein Token ausgegeben").doesNotContain("ua_");
    }
}
