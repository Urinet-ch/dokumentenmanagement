package ch.urinet.archiv.ablage;

import ch.urinet.archiv.gemeinsam.SecretDateienTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stufen-Prüfung der Ablage (Req 6.11, tasks.md 2.3): Ohne {@code archiv.stufe} gilt Stufe B; eine Höchstfrist für
 * {@code archiv/} bricht dann auch den Start des Workers ab, unabhängig vom Profil.
 */
@QuarkusMainTest
@TestProfile(AblageStufeWorkerStartTest.WorkerOhneStufeMitHoechstfrist.class)
class AblageStufeWorkerStartTest {

    public static class WorkerOhneStufeMitHoechstfrist implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> werte = new HashMap<>(SecretDateienTest.prodOhneDienste("vollstaendig"));
            werte.put("archiv.rolle", "worker");
            werte.put("archiv.ablage.max-sperrfrist", "P1D");
            werte.put("quarkus.http.port", "0");
            return werte;
        }
    }

    @Test
    @Launch(value = {}, exitCode = 1)
    void startBrichtAb(LaunchResult ergebnis) {
        assertThat(ergebnis.getOutput() + ergebnis.getErrorOutput())
                .contains("Ablage ab Stufe B", "archiv.ablage.max-sperrfrist")
                .doesNotContain("archiv.ablage.manifest-sperrmodus");
    }
}
