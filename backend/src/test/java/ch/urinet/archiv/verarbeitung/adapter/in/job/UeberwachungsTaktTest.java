package ch.urinet.archiv.verarbeitung.adapter.in.job;

import ch.urinet.archiv.verarbeitung.application.Ueberwachung;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class UeberwachungsTaktTest {

    @Test
    @Tag("req-16.24")
    void stufePUeberspringtDieUeberwachung() {
        AtomicBoolean geprueft = new AtomicBoolean();
        Ueberwachung ueberwachung = new Ueberwachung(null, null, null, null, null, null, null, null,
                Duration.ofMinutes(1),
                "urinet") {
            @Override
            public int pruefen() {
                geprueft.set(true);
                return 0;
            }
        };
        UeberwachungsTakt takt = new UeberwachungsTakt(ueberwachung, "worker", "P");

        takt.pruefen();

        assertThat(geprueft.get()).isFalse();
    }
}
