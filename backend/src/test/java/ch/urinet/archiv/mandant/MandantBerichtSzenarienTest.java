package ch.urinet.archiv.mandant;

import ch.urinet.archiv.protokoll.TsaTestRessource;
import io.quarkiverse.cucumber.CucumberOptions;
import io.quarkiverse.cucumber.CucumberQuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Tag;

import java.util.Map;
import java.util.UUID;

/** Isolierter Cucumber-Lauf für den monatlichen Mandantenbericht. */
@Tag("start-3")
@QuarkusTest
@QuarkusTestResource(TsaTestRessource.class)
@TestProfile(MandantBerichtSzenarienTest.BerichtProfil.class)
@CucumberOptions(features = "classpath:features/mandant/monatsbericht.feature", glue = "ch.urinet.archiv",
        tags = "@mandant-bericht")
public class MandantBerichtSzenarienTest extends CucumberQuarkusTest {

    public static void main(String[] args) {
        runMain(MandantBerichtSzenarienTest.class, args);
    }

    public static class BerichtProfil implements QuarkusTestProfile {
        private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.datasource.devservices.db-name", "archiv_bericht_" + SUFFIX);
        }
    }
}
