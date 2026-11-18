package ch.urinet.archiv;

import ch.urinet.archiv.protokoll.TsaTestRessource;
import io.quarkiverse.cucumber.CucumberOptions;
import io.quarkiverse.cucumber.CucumberQuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Tag;

/**
 * Java-Runner für die Backend-Szenarien unter {@code features/<fachbereich>/} (tech.md, Tests und Qualität).
 * Die Zeitstempeldienste ({@link TsaTestRessource}) gelten global für alle Quarkus-Tests im Profil {@code test}.
 * Szenarien mit {@code @browser} laufen mit Cucumber.js + Playwright gegen den Prod-Build (frontend/cucumber.mjs).
 * Shard 2 von 2 ({@link SzenarienShards}): alle Szenarien ohne {@code @spec-01}, auch solche ohne Spec-Tag; {@code @spec-01} läuft in {@link SzenarienTest}.
 */
@QuarkusTest
@Tag("szenarien-2")
@QuarkusTestResource(TsaTestRessource.class)
@CucumberOptions(features = "classpath:features", glue = "ch.urinet.archiv",
        tags = SzenarienShards.SHARD_2)
public class SzenarienRestTest extends CucumberQuarkusTest {

    public static void main(String[] args) {
        runMain(SzenarienRestTest.class, args);
    }
}
