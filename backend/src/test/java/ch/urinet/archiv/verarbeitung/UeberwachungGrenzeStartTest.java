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

/**
 * Eine Grenze der Überwachung von 0 bricht den Start ab (Req 16.5, Review PR #91), statt dass die Alarme im Worker still
 * ausfallen. Über das Admin-Kommando wie {@code WartezeitNegativStartTest}, weil der Dienst bei gelungenem Start nie
 * endet; der unbekannte Mandant lässt einen trotzdem gelungenen Start mit Exit 1 statt 3 enden.
 */
@Tag("start-1")
@QuarkusMainTest
@TestProfile(UeberwachungGrenzeStartTest.Einstellung.class)
class UeberwachungGrenzeStartTest {

    public static class Einstellung implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("archiv.ueberwachung.grenze", "0s");
        }
    }

    @AfterAll
    static void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
    }

    @Test
    @Launch(value = {"token", "erstellen", "probe-gibts-nicht", "Probe", "import"}, exitCode = 3)
    void startBrichtAb(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains("Start fehlgeschlagen", "archiv.ueberwachung.grenze");
        assertThat(ergebnis.getOutput()).as("kein Token ausgegeben").doesNotContain("ua_");
    }
}
