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
 * Stufen-Prüfung der Ablage (Req 6.11, tasks.md 2.3): Ab Stufe B bricht der Start der API ab, wenn Manifeste nicht in
 * COMPLIANCE gesperrt würden. Der Dienst startet ohne Argumente; gelänge der Start, endete der Test nicht.
 */
@QuarkusMainTest
@TestProfile(AblageStufeApiStartTest.ApiStufeBGovernance.class)
class AblageStufeApiStartTest {

    public static class ApiStufeBGovernance implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> werte = new HashMap<>(SecretDateienTest.prodOhneDienste("vollstaendig"));
            werte.put("archiv.rolle", "api");
            werte.put("archiv.stufe", "B");
            werte.put("archiv.ablage.manifest-sperrmodus", "GOVERNANCE");
            werte.put("quarkus.http.port", "0");
            return werte;
        }
    }

    @Test
    @Launch(value = {}, exitCode = 1)
    void startBrichtAb(LaunchResult ergebnis) {
        assertThat(ergebnis.getOutput() + ergebnis.getErrorOutput())
                .contains("Ablage ab Stufe B", "archiv.ablage.manifest-sperrmodus");
    }
}
