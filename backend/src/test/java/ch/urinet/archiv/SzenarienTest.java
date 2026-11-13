package ch.urinet.archiv;

import ch.urinet.archiv.protokoll.TsaTestRessource;
import io.quarkiverse.cucumber.CucumberOptions;
import io.quarkiverse.cucumber.CucumberQuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Java-Runner für die Backend-Szenarien unter {@code features/<fachbereich>/} (tech.md, Tests und Qualität).
 * Die Zeitstempeldienste ({@link TsaTestRessource}) gelten global für alle Quarkus-Tests im Profil {@code test}.
 * Szenarien mit {@code @browser} laufen mit Cucumber.js + Playwright gegen den Prod-Build (frontend/cucumber.mjs).
 */
@QuarkusTest
@QuarkusTestResource(TsaTestRessource.class)
@CucumberOptions(features = "classpath:features", glue = "ch.urinet.archiv", tags = "not @browser")
public class SzenarienTest extends CucumberQuarkusTest {

    public static void main(String[] args) {
        runMain(SzenarienTest.class, args);
    }
}
