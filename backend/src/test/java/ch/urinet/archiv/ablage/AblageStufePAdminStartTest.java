package ch.urinet.archiv.ablage;

import ch.urinet.archiv.gemeinsam.SecretDateienTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("start-2")
@QuarkusMainTest
@TestProfile(AblageStufePAdminStartTest.AdminMitUngueltigerStufeP.class)
class AblageStufePAdminStartTest {

    public static class AdminMitUngueltigerStufeP implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> werte = new HashMap<>(SecretDateienTest.prodOhneDienste("vollstaendig"));
            werte.put("archiv.rolle", "kommando");
            werte.put("archiv.stufe", "P");
            werte.put("archiv.ablage.manifest-sperrmodus", "GOVERNANCE");
            werte.put("quarkus.http.port", "0");
            return werte;
        }
    }

    @Test
    @Tag("req-16.24")
    @Launch(value = {"mandant", "anlegen", "probe-start"}, exitCode = 3)
    void startBrichtBeiFalschemManifestSperrmodusAb(LaunchResult ergebnis) {
        assertThat(ergebnis.getOutput() + ergebnis.getErrorOutput())
                .contains("Ablage Stufe P", "archiv.ablage.manifest-sperrmodus");
    }
}
