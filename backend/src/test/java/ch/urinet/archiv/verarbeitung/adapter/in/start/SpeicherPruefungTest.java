package ch.urinet.archiv.verarbeitung.adapter.in.start;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Die Startprüfung der Speicherschwellen (Req 16.5) bricht bei ungeordneten Werten ab, statt still zu schweigen. */
class SpeicherPruefungTest {

    @Test
    @Tag("req-16.5")
    void ungueltigeSchwellenBrechenDenStartAb() {
        SpeicherPruefung pruefung = new SpeicherPruefung();

        assertThatThrownBy(() -> pruefung.beimStart(null, 95, 85)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archiv.ueberwachung.speicher");
        assertThatThrownBy(() -> pruefung.beimStart(null, 85, 85)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pruefung.beimStart(null, -1, 90)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pruefung.beimStart(null, 90, 150)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("req-16.5")
    void gueltigeSchwellenLassenDenStartZu() {
        SpeicherPruefung pruefung = new SpeicherPruefung();

        assertThatCode(() -> pruefung.beimStart(null, 85, 95)).doesNotThrowAnyException();
        assertThatCode(() -> pruefung.beimStart(null, 1, 100)).doesNotThrowAnyException();
    }
}
