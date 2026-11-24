package ch.urinet.archiv.benutzer.adapter.in.kommando;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BenutzerAbgleichenKommandoTest {
    @Test
    void technischeDienstfehlerErhaltenExitCodeDrei() {
        assertThat(BenutzerAbgleichenKommando.exitCode(
                new FachFehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR))).isEqualTo(3);
    }

    @Test
    void fachlicheFehlerErhaltenExitCodeEins() {
        assertThat(BenutzerAbgleichenKommando.exitCode(
                new FachFehler(Fehlercode.MANDANT_FEHLT))).isEqualTo(1);
    }
}
