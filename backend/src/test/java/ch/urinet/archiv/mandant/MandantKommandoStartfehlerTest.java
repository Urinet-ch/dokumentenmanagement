package ch.urinet.archiv.mandant;

import ch.urinet.archiv.ArchivMain;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Scheitert schon der Start (falscher Zugang für den Eigentümer), endet das Admin-Kommando mit 3, nicht mit 1. */
@Tag("start-1")
@QuarkusMainTest
@TestProfile(MandantKommandoStartfehlerTest.FalscherEigentuemer.class)
class MandantKommandoStartfehlerTest {

    public static class FalscherEigentuemer implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.liquibase.username", "kein_eigentuemer",
                    "quarkus.liquibase.password", "falsch");
        }
    }

    @AfterAll
    static void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
    }

    @Test
    @Launch(value = {"mandant", "anlegen", "probe-startfehler"}, exitCode = 3)
    void startfehler(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains("Start fehlgeschlagen", "kein_eigentuemer")
                .doesNotContain("MANDANT_EXISTIERT");
    }
}
