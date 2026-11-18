package ch.urinet.archiv.verarbeitung;

import io.quarkiverse.cucumber.CucumberOptions;
import io.quarkiverse.cucumber.CucumberQuarkusTest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Tag;

import java.util.Map;
import java.util.UUID;

/** Isolierter Cucumber-Lauf mit eingeschalteter Sicherungsüberwachung und eigenem Dev-Services-Datenbanknamen. */
@Tag("start-3")
@QuarkusTest
@TestProfile(SicherungUeberwachungSzenarienTest.SicherungAktiv.class)
@CucumberOptions(features = "classpath:features/verarbeitung/ueberwachung.feature", glue = "ch.urinet.archiv",
        tags = "@sicherung")
public class SicherungUeberwachungSzenarienTest extends CucumberQuarkusTest {

    public static void main(String[] args) {
        runMain(SicherungUeberwachungSzenarienTest.class, args);
    }

    public static class SicherungAktiv implements QuarkusTestProfile {
        private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "archiv.ueberwachung.sicherung-aktiv", "true",
                    "archiv.ueberwachung.betrieb-mandant", "urinet",
                    "archiv.benachrichtigung.empfaenger.urinet", "inhaber+" + SUFFIX + "@example.invalid",
                    "quarkus.datasource.devservices.db-name", "archiv_sicherung_" + SUFFIX);
        }
    }
}
