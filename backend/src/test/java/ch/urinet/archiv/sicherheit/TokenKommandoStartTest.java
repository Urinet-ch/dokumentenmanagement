package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.ArchivMain;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code archiv token erstellen} über denselben Einstieg wie der Dienst, ohne Server (Req 2.5, 2.20). */
@QuarkusMainTest
class TokenKommandoStartTest {

    @AfterAll
    static void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
    }

    @Test
    void erstelltEinTokenOhneServerUndOhneGeheimnisImLog(QuarkusMainLauncher starter) {
        // Seed gibt es nur für urinet und vhf-gsk; besteht der Mandant schon, ist das Exit 1
        LaunchResult mandant = starter.launch("mandant", "anlegen", "urinet");
        assertThat(mandant.exitCode()).as(mandant.getErrorOutput()).isIn(0, 1);

        LaunchResult ergebnis = starter.launch("token", "erstellen", "urinet", "Probe Kommando", "import");

        assertThat(ergebnis.exitCode()).as(ergebnis.getErrorOutput()).isZero();
        String token = ergebnis.getOutputStream().stream().filter(z -> z.startsWith(ApiToken.PRAEFIX)).findFirst()
                .orElseThrow();
        String geheimnis = ApiToken.lesen(token).orElseThrow().geheimnis();
        assertThat(ergebnis.getOutputStream()).as("Geheimnis nur in der Token-Zeile")
                .filteredOn(z -> z.contains(geheimnis)).containsExactly(token);
        assertThat(ergebnis.getErrorOutput()).doesNotContain(geheimnis);
        assertThat(ergebnis.getOutput()).doesNotContain("Listening on: http");
    }

    @Test
    void unbekannterMandantIstExit1(QuarkusMainLauncher starter) {
        LaunchResult ergebnis = starter.launch("token", "erstellen", "probe-gibts-nicht", "Probe", "import");

        assertThat(ergebnis.exitCode()).isEqualTo(1);
        assertThat(ergebnis.getErrorOutput()).contains("MANDANT_FEHLT");
    }

    @Test
    void falscherAufrufOhneStart(QuarkusMainLauncher starter) {
        LaunchResult ergebnis = starter.launch("token", "erstellen", "urinet");

        assertThat(ergebnis.exitCode()).isEqualTo(2);
        assertThat(ergebnis.getErrorOutput()).contains("Aufruf: archiv token erstellen");
        assertThat(ergebnis.getOutput()).doesNotContain("Installed features");
    }
}
