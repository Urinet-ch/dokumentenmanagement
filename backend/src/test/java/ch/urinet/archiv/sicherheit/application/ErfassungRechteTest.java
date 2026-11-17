package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ErfassungRechteTest {

    @Test
    void festePositivlisteEntsprichtReq10Punkt8UndDerRechtematrix() {
        Set<Aktion> erwartet = EnumSet.of(Aktion.A1, Aktion.A2, Aktion.A3, Aktion.A4, Aktion.A5, Aktion.A6,
                Aktion.A7, Aktion.A8, Aktion.A10);
        Set<Aktion> erlaubt = Arrays.stream(Aktion.values())
                .filter(Berechtigung::webseitenErfassungErlaubt)
                .collect(Collectors.toSet());

        assertThat(erlaubt).isEqualTo(erwartet);
        assertThat(erlaubt).allSatisfy(aktion ->
                assertThat(Rechtematrix.ergebnis(Rolle.GESCHAEFTSSTELLE, aktion).zugaenglich()).isTrue());
    }
}
