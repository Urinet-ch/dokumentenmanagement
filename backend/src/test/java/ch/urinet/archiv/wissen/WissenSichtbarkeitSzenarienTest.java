package ch.urinet.archiv.wissen;

import ch.urinet.archiv.protokoll.TsaTestRessource;
import io.quarkiverse.cucumber.CucumberOptions;
import io.quarkiverse.cucumber.CucumberQuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(TsaTestRessource.class)
@CucumberOptions(features = "classpath:features/wissen/sichtbarkeit-rechte.feature", glue = "ch.urinet.archiv",
        tags = "@wissen-sichtbarkeit")
public class WissenSichtbarkeitSzenarienTest extends CucumberQuarkusTest {

    public static void main(String[] args) {
        runMain(WissenSichtbarkeitSzenarienTest.class, args);
    }
}
