package ch.urinet.archiv.benutzer.adapter.in.kommando;

import ch.urinet.archiv.benutzer.application.BenutzerAbgleichen;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BenutzerAbgleichenKommandoTest {
    @Test
    void technischeDienstfehlerErhaltenExitCodeDrei() {
        assertThat(BenutzerAbgleichenKommando.exitCode(
                new FachFehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR))).isEqualTo(3);
        assertThat(BenutzerAbgleichenKommando.exitCode(
                new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER))).isEqualTo(3);
    }

    @Test
    void fachlicheFehlerErhaltenExitCodeEins() {
        assertThat(BenutzerAbgleichenKommando.exitCode(
                new FachFehler(Fehlercode.MANDANT_FEHLT))).isEqualTo(1);
    }

    @Test
    void dienstkontoFehlerErgibtBeimAusfuehrenExitCodeDrei() {
        FachFehler fehler = new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        BenutzerAbgleichen abgleichen = new BenutzerAbgleichen(null, null, null, null, null, "urinet") {
            @Override
            public Bericht ausfuehren(MandantCode mandant, boolean pruefen) {
                throw fehler;
            }
        };
        var ausgabe = new ByteArrayOutputStream();
        var fehlerAusgabe = new ByteArrayOutputStream();
        BenutzerAbgleichenKommando kommando = new BenutzerAbgleichenKommando(abgleichen, new ObjectMapper());

        try (var aus = new PrintStream(ausgabe, true, StandardCharsets.UTF_8);
             var fehlerStream = new PrintStream(fehlerAusgabe, true, StandardCharsets.UTF_8)) {
            assertThat(kommando.ausfuehren(List.of("abgleichen", "urinet"), aus, fehlerStream)).isEqualTo(3);
        }

        assertThat(fehlerAusgabe.toString(StandardCharsets.UTF_8)).contains("IDENTITAETSDIENST_FEHLER");
        assertThat(ausgabe.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
