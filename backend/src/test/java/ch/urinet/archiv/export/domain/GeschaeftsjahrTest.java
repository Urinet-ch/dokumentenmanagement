package ch.urinet.archiv.export.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Geschäftsjahr und Frist des Download-Links im Jahresexport (Spec 02, Req 7.1, 7.2). */
class GeschaeftsjahrTest {

    @Test
    void kalenderjahrUndAbweichendesEnde() {
        Geschaeftsjahr kalender = new Geschaeftsjahr(2026, MonthDay.of(12, 31));
        assertThat(kalender.ersterTag()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(kalender.letzterTag()).isEqualTo(LocalDate.of(2026, 12, 31));
        Geschaeftsjahr juni = new Geschaeftsjahr(2027, MonthDay.of(6, 30));
        assertThat(juni.ersterTag()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(juni.letzterTag()).isEqualTo(LocalDate.of(2027, 6, 30));
        Geschaeftsjahr schalttag = new Geschaeftsjahr(2025, MonthDay.of(2, 29));
        assertThat(schalttag.ersterTag()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(schalttag.letzterTag()).isEqualTo(LocalDate.of(2025, 2, 28));
    }

    @Test
    void grenzen() {
        assertThat(Geschaeftsjahr.gueltig(1900)).isTrue();
        assertThat(Geschaeftsjahr.gueltig(2999)).isTrue();
        assertThat(Geschaeftsjahr.gueltig(1899)).isFalse();
        assertThat(Geschaeftsjahr.gueltig(3000)).isFalse();
        assertThatThrownBy(() -> new Geschaeftsjahr(3000, MonthDay.of(12, 31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void downloadGilt24StundenAbFertigstellung() {
        Instant fertig = Instant.parse("2026-11-13T10:00:00Z");
        assertThat(Exportfrist.gueltigBis(fertig)).isEqualTo(Instant.parse("2026-11-14T10:00:00Z"));
        assertThat(Exportfrist.abgelaufen(fertig, Instant.parse("2026-11-14T09:59:59Z"))).isFalse();
        assertThat(Exportfrist.abgelaufen(fertig, Instant.parse("2026-11-14T10:00:00Z"))).isTrue();
    }
}
