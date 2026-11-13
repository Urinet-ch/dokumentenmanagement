package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.ArchivMain;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code archiv token erstellen} über denselben Einstieg wie der Dienst, ohne Server (Req 2.5, 2.20). Das README leitet
 * stdout in die Token-Datei um, darum darf dort nur das Token stehen. Der Mitschnitt von {@code @QuarkusMainTest}
 * zählt jede Zeile des Konsolen-Logs zu stdout, auch mit {@code quarkus.log.console.stderr=true}; das Log geht in
 * diesem Test darum in eine Datei, und stdout wird ungefiltert geprüft.
 */
@Tag("start-2")
@QuarkusMainTest
@TestProfile(TokenKommandoStartTest.LogInDatei.class)
class TokenKommandoStartTest {

    static final Path LOG = Path.of("target", "token-kommando-test.log");

    public static class LogInDatei implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.log.console.enable", "false",
                    "quarkus.log.file.enable", "true",
                    "quarkus.log.file.path", LOG.toString());
        }
    }

    @AfterAll
    static void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
    }

    @Test
    void stdoutEnthaeltGenauDasTokenUndKeinLogDasGeheimnis(QuarkusMainLauncher starter) throws IOException {
        // Seed gibt es nur für urinet und vhf-gsk; besteht der Mandant schon, ist das Exit 1
        LaunchResult mandant = starter.launch("mandant", "anlegen", "urinet");
        assertThat(mandant.exitCode()).as(mandant.getErrorOutput()).isIn(0, 1);

        LaunchResult ergebnis = starter.launch("token", "erstellen", "urinet", "Probe Kommando", "import");

        assertThat(ergebnis.exitCode()).as(ergebnis.getErrorOutput()).isZero();
        assertThat(ergebnis.getOutputStream()).as("stdout").hasSize(1);
        String token = ergebnis.getOutputStream().getFirst();
        String geheimnis = ApiToken.lesen(token).orElseThrow().geheimnis();
        assertThat(ergebnis.getErrorOutput()).doesNotContain(geheimnis);
        String log = Files.readString(LOG);
        assertThat(log).contains("Installed features").doesNotContain("Listening on: http").doesNotContain(geheimnis);
        // Kann 4 aus dem Review von PR #37: Liquibase (UI-Meldungen und Zusammenfassung der public-Migration) nur ins Log
        assertThat(log).contains("\"loggerName\":\"liquibase.ui\"", "UPDATE SUMMARY");
        assertThat(ergebnis.getErrorOutput()).doesNotContain("UPDATE SUMMARY", "Database is up to date");
        // Der Mitschnitt trennt Konsolen-Log und stdout nicht (siehe Klassenkommentar); darum hier die Einstellung selbst
        assertThat(System.getProperty("quarkus.log.console.stderr")).as("Konsolen-Log im Kommando-Modus").isEqualTo("true");
    }

    @Test
    void unbekannterMandantIstExit1(QuarkusMainLauncher starter) {
        LaunchResult ergebnis = starter.launch("token", "erstellen", "probe-gibts-nicht", "Probe", "import");

        assertThat(ergebnis.exitCode()).isEqualTo(1);
        assertThat(ergebnis.getErrorOutput()).contains("MANDANT_FEHLT");
        assertThat(ergebnis.getOutput()).isEmpty();
    }

    @Test
    void falscherAufrufOhneStart(QuarkusMainLauncher starter) {
        LaunchResult ergebnis = starter.launch("token", "erstellen", "urinet");

        assertThat(ergebnis.exitCode()).isEqualTo(2);
        assertThat(ergebnis.getErrorOutput()).contains("Aufruf: archiv token erstellen");
        assertThat(ergebnis.getOutput()).isEmpty();
    }
}
