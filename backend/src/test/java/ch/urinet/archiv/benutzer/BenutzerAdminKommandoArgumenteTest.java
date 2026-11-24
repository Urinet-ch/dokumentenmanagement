package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.adapter.in.kommando.BenutzerAbgleichenKommando;
import ch.urinet.archiv.benutzer.adapter.in.kommando.BenutzerMandantSperrenKommando;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BenutzerAdminKommandoArgumenteTest {

    @Test
    void abgleichenAkzeptiertPrueflaufUndJsonOhneDoppelteOptionen() {
        assertThat(BenutzerAbgleichenKommando.lesen(
                List.of("abgleichen", "urinet", "--pruefen", "--json"))).isPresent();
        assertThat(BenutzerAbgleichenKommando.lesen(
                List.of("abgleichen", "urinet", "--pruefen", "--pruefen"))).isEmpty();
        assertThat(BenutzerAbgleichenKommando.lesen(
                List.of("abgleichen", "urinet", "--falsch"))).isEmpty();
        assertThat(BenutzerAbgleichenKommando.lesen(
                List.of("abgleichen", "Ungueltig"))).isEmpty();
    }

    @Test
    void mandatensperreAkzeptiertNurOptionJson() {
        assertThat(BenutzerMandantSperrenKommando.lesen(
                List.of("benutzer-sperren", "vhf-gsk", "--json"))).isPresent();
        assertThat(BenutzerMandantSperrenKommando.lesen(
                List.of("benutzer-sperren", "vhf-gsk", "--json", "--json"))).isEmpty();
        assertThat(BenutzerMandantSperrenKommando.lesen(
                List.of("benutzer-sperren", "vhf-gsk", "--ausfuehren"))).isEmpty();
    }
}
