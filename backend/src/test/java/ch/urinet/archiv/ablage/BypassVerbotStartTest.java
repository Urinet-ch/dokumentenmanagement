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

/**
 * Bypass-Verbot beim Start (Req 6.5, Review PR #43 K1): Fehlt {@code BypassVerbot} am S3-Client, bricht der Start ab,
 * bevor ein Zugriff auf die Ablage erfolgt. Gelänge der Start, endete der Test nicht.
 */
@Tag("start-2")
@QuarkusMainTest
@TestProfile(BypassVerbotStartTest.OhneBypassVerbot.class)
class BypassVerbotStartTest {

    public static class OhneBypassVerbot implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> werte = new HashMap<>(SecretDateienTest.prodOhneDienste("vollstaendig"));
            werte.put("archiv.rolle", "api");
            werte.put("quarkus.s3.interceptors", KeinBypassInterceptor.class.getName());
            werte.put("quarkus.http.port", "0");
            return werte;
        }
    }

    @Test
    @Launch(value = {}, exitCode = 1)
    void startBrichtAb(LaunchResult ergebnis) {
        assertThat(ergebnis.getOutput() + ergebnis.getErrorOutput()).contains("S3-Client ohne", "BypassVerbot");
    }
}
