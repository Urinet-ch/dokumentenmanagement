package ch.urinet.archiv.verarbeitung.adapter.in.job;

import ch.urinet.archiv.verarbeitung.application.SpeicherUeberwachung;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Der Takt der Speicher-Frühwarnung (Req 16.5): läuft in jeder Rolle, nicht in Stufe P, und bricht nie ab. */
class SpeicherTaktTest {

    private static SpeicherUeberwachung ueberwachung(AtomicInteger messungen, boolean ausfall) {
        return new SpeicherUeberwachung(() -> {
            messungen.incrementAndGet();
            if (ausfall) {
                throw new IllegalStateException("synthetisch");
            }
            return List.of();
        }, (stufe, messung) -> true, (pool, prozent) -> { }, 85, 95);
    }

    @Test
    @Tag("req-16.5")
    void stufePUeberspringtDieUeberwachung() {
        AtomicInteger messungen = new AtomicInteger();

        new SpeicherTakt(ueberwachung(messungen, false), "P").pruefen();

        assertThat(messungen).hasValue(0);
    }

    @Test
    @Tag("req-16.5")
    void stufenAundBUndFehlendeStufePruefen() {
        AtomicInteger messungen = new AtomicInteger();

        new SpeicherTakt(ueberwachung(messungen, false), "A").pruefen();
        new SpeicherTakt(ueberwachung(messungen, false), "B").pruefen();
        new SpeicherTakt(ueberwachung(messungen, false), null).pruefen();

        assertThat(messungen).hasValue(3);
    }

    @Test
    @Tag("req-16.5")
    void einAusfallDerMessungBrichtDenTaktNichtAb() {
        AtomicInteger messungen = new AtomicInteger();
        SpeicherTakt takt = new SpeicherTakt(ueberwachung(messungen, true), "B");

        assertThatCode(takt::pruefen).doesNotThrowAnyException();
        assertThatCode(takt::pruefen).doesNotThrowAnyException();

        assertThat(messungen).hasValue(2);
    }
}
