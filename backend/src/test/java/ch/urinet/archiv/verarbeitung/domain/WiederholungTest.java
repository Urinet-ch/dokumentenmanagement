package ch.urinet.archiv.verarbeitung.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Req 5.4: drei Versuche, Wartezeit 2^n Minuten nach dem n-ten Fehlversuch. */
class WiederholungTest {

    @Test
    void wartezeitVerdoppeltSichBisZumDrittenVersuch() {
        assertThat(Wiederholung.nachFehlversuch(1)).contains(Duration.ofMinutes(2));
        assertThat(Wiederholung.nachFehlversuch(2)).contains(Duration.ofMinutes(4));
        assertThat(Wiederholung.nachFehlversuch(3)).isEmpty();
        assertThat(Wiederholung.nachFehlversuch(4)).isEmpty();
        assertThatThrownBy(() -> Wiederholung.nachFehlversuch(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
