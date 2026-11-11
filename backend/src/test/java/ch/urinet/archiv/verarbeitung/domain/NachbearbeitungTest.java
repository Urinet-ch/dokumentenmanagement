package ch.urinet.archiv.verarbeitung.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Req 5.4: Hook nach dem letzten Fehlversuch höchstens fünfmal, Wartezeit 2^n Minuten nach dem n-ten Fehlschlag. */
class NachbearbeitungTest {

    @Test
    void wartezeitVerdoppeltSichBisZurObergrenze() {
        assertThat(Nachbearbeitung.nachFehlschlag(1)).contains(Duration.ofMinutes(2));
        assertThat(Nachbearbeitung.nachFehlschlag(4)).contains(Duration.ofMinutes(16));
        assertThat(Nachbearbeitung.nachFehlschlag(5)).isEmpty();
        assertThatThrownBy(() -> Nachbearbeitung.nachFehlschlag(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
