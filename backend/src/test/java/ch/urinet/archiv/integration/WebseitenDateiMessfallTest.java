package ch.urinet.archiv.integration;

import ch.urinet.archiv.SzenarienShards;
import ch.urinet.archiv.protokoll.TsaTestRessource;
import io.quarkiverse.cucumber.CucumberOptions;
import io.quarkiverse.cucumber.CucumberQuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Tag;

import java.util.Map;

/** Isoliert die Heap-Messung vom kürzeren Leerlauflimit der übrigen Webseiten-Szenarien. */
@Tag("start-1")
@QuarkusTest
@QuarkusTestResource(TsaTestRessource.class)
@TestProfile(WebseitenDateiMessfallTest.MessfallProfil.class)
@CucumberOptions(features = "classpath:features/integration/webseiten-datei.feature", glue = "ch.urinet.archiv",
        tags = SzenarienShards.SHARD_3)
public class WebseitenDateiMessfallTest extends CucumberQuarkusTest {

    public static void main(String[] args) {
        runMain(WebseitenDateiMessfallTest.class, args);
    }

    public static class MessfallProfil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("archiv.webseite.stream-leerlauf", "PT30S");
        }
    }
}
