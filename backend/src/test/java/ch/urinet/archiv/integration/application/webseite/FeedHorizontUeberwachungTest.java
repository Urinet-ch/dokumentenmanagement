package ch.urinet.archiv.integration.application.webseite;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Warnung bei Stillstand des Horizonts des Feeds (Spec 03, Aufgabe 11.9, design.md «Grenze des Cursors»). */
class FeedHorizontUeberwachungTest {

    private static final Instant T0 = Instant.parse("2026-11-16T10:00:00Z");

    private final FeedHorizontUeberwachung ueberwachung = new FeedHorizontUeberwachung(null, Duration.ofMinutes(10));

    @Test
    void einHorizontDerSichBewegtWarntNie() {
        assertThat(ueberwachung.beobachten(100, 110, T0)).isEmpty();
        assertThat(ueberwachung.beobachten(101, 111, T0.plus(Duration.ofHours(1)))).isEmpty();
    }

    @Test
    void stehtDerHorizontMitOffenenTransaktionenLaenger_alsDieGrenzeStill_kommtEineWarnung() {
        ueberwachung.beobachten(100, 110, T0);

        assertThat(ueberwachung.beobachten(100, 112, T0.plus(Duration.ofMinutes(9)))).isEmpty();
        assertThat(ueberwachung.beobachten(100, 115, T0.plus(Duration.ofMinutes(11))))
                .contains(Duration.ofMinutes(11));
    }

    @Test
    void eineRuhigeDatenbankOhneOffeneTransaktionIstKeinStillstand() {
        ueberwachung.beobachten(100, 100, T0);

        assertThat(ueberwachung.beobachten(100, 100, T0.plus(Duration.ofHours(3)))).isEmpty();
        // danach ist eine Transaktion offen: die Frist beginnt erst jetzt
        assertThat(ueberwachung.beobachten(100, 101, T0.plus(Duration.ofHours(3)).plusSeconds(1))).isEmpty();
    }

    @Test
    void nachDemEndeDerTransaktionIstDieWarnungVorbei() {
        ueberwachung.beobachten(100, 110, T0);
        assertThat(ueberwachung.beobachten(100, 110, T0.plus(Duration.ofMinutes(30)))).isPresent();

        assertThat(ueberwachung.beobachten(120, 120, T0.plus(Duration.ofMinutes(31)))).isEmpty();
    }
}
