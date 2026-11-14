package ch.urinet.archiv.ki.domain.chat;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code Retry-After}: Sekunden, bis die massgebende Frage eine Stunde alt ist (Req 5.5). */
class FragegrenzeTest {

    private static final Instant JETZT = Instant.parse("2026-11-14T10:00:00Z");

    @Test
    void sekundenBisZumAblaufDerStunde() {
        assertThat(Fragegrenze.wartezeitSekunden(Instant.parse("2026-11-14T09:01:00Z"), JETZT)).isEqualTo(60);
    }

    @Test
    void angebrocheneSekundeWirdAufgerundet() {
        assertThat(Fragegrenze.wartezeitSekunden(Instant.parse("2026-11-14T09:00:00.200Z"), JETZT)).isEqualTo(1);
        assertThat(Fragegrenze.wartezeitSekunden(Instant.parse("2026-11-14T09:30:10.001Z"), JETZT)).isEqualTo(1811);
    }

    @Test
    void mindestensEineSekunde() {
        assertThat(Fragegrenze.wartezeitSekunden(Instant.parse("2026-11-14T09:00:00Z"), JETZT)).isEqualTo(1);
        assertThat(Fragegrenze.wartezeitSekunden(Instant.parse("2026-11-14T08:00:00Z"), JETZT)).isEqualTo(1);
    }

    @Test
    void eineEbenGestellteFrageHeisstEineStunde() {
        assertThat(Fragegrenze.wartezeitSekunden(JETZT, JETZT)).isEqualTo(3600);
    }
}
