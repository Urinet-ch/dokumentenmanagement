package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.ArchivMain;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fehlt in {@code prod} eine Secret-Datei ({@code archiv_db_app}), bricht der Start ab, statt mit leerem Passwort
 * weiterzulaufen (fail closed, tech.md Regel 7). Über das Admin-Kommando, weil der Dienst bei gelungenem Start nie endet.
 */
@QuarkusMainTest
@TestProfile(SecretDateiFehltTest.ProdOhneDbApp.class)
class SecretDateiFehltTest {

    public static class ProdOhneDbApp implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return SecretDateienTest.prodOhneDienste("ohne-db-app");
        }
    }

    @AfterAll
    static void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
    }

    @Test
    @Launch(value = {"mandant", "anlegen", "probe-secret"}, exitCode = 3)
    void startBrichtAb(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains("Start fehlgeschlagen", "archiv_db_app")
                .doesNotContain("attrappe");
        assertThat(ergebnis.getOutput()).doesNotContain("angelegt");
    }
}
