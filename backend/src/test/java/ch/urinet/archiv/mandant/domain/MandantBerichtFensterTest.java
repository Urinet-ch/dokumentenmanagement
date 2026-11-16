package ch.urinet.archiv.mandant.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class MandantBerichtFensterTest {

    @Test
    void vormonatBestimmtKalendermonatInZuerich() {
        MandantBerichtFenster fenster = MandantBerichtFenster.vormonat(
                Instant.parse("2026-04-01T00:15:00+02:00"));

        assertThat(fenster.monat()).isEqualTo(YearMonth.of(2026, 3));
        assertThat(fenster.von()).isEqualTo(Instant.parse("2026-02-28T23:00:00Z"));
        assertThat(fenster.bis()).isEqualTo(Instant.parse("2026-03-31T22:00:00Z"));
    }

    @Test
    void monatsfensterVerwendetOrtsmitternachtAuchBeimSommerzeitwechsel() {
        MandantBerichtFenster fenster = MandantBerichtFenster.fuer(YearMonth.of(2026, 3));

        assertThat(fenster.ersterTag()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(fenster.von()).isEqualTo(Instant.parse("2026-02-28T23:00:00Z"));
        assertThat(fenster.bis()).isEqualTo(Instant.parse("2026-03-31T22:00:00Z"));
        assertThat(fenster.bis().getEpochSecond() - fenster.von().getEpochSecond())
                .isEqualTo(31L * 24 * 60 * 60 - 60 * 60);
    }
}
