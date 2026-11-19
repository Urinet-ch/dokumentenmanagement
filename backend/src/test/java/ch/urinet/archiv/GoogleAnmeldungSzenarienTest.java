package ch.urinet.archiv;

import ch.urinet.archiv.sicherheit.ExportKeycloak;
import io.quarkiverse.cucumber.CucumberOptions;
import io.quarkiverse.cucumber.CucumberQuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;

/** Echte OIDC-Broker-Szenarien gegen Keycloak und den lokalen Test-IdP. */
@Tag("start-3")
@TestProfile(GoogleAnmeldungSzenarienTest.MitExport.class)
@CucumberOptions(features = "classpath:features/sicherheit/google-anmeldung.feature", glue = "ch.urinet.archiv",
        tags = "@google-oidc")
public class GoogleAnmeldungSzenarienTest extends CucumberQuarkusTest {

    public static class MitExport implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.keycloak.devservices.enabled", "false");
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(ExportKeycloak.class));
        }
    }

    public static void main(String[] args) {
        runMain(GoogleAnmeldungSzenarienTest.class, args);
    }
}
