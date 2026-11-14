package ch.urinet.archiv.ki.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 02, Req 3.7 und «Vertrag Welle 7»: Beträge und Daten gehen vorformatiert an das Sprachmodell. */
class WerteformatTest {

    @Test
    void betraegeMitHochkommaUndZweiStellen() {
        assertThat(Werteformat.chf(new BigDecimal("1234.5"))).isEqualTo("CHF 1'234.50");
        assertThat(Werteformat.chf(new BigDecimal("-1234.50"))).isEqualTo("CHF -1'234.50");
        assertThat(Werteformat.chf(new BigDecimal("0"))).isEqualTo("CHF 0.00");
        assertThat(Werteformat.chf(new BigDecimal("999.99"))).isEqualTo("CHF 999.99");
        assertThat(Werteformat.chf(new BigDecimal("1000"))).isEqualTo("CHF 1'000.00");
        assertThat(Werteformat.chf(new BigDecimal("9999999999.99"))).isEqualTo("CHF 9'999'999'999.99");
        assertThat(Werteformat.chf(new BigDecimal("-0.5"))).isEqualTo("CHF -0.50");
        assertThat(Werteformat.chf(new BigDecimal("12.345"))).isEqualTo("CHF 12.35");
        assertThat(Werteformat.chf(null)).isNull();
    }

    @Test
    void andereWaehrungOhneUmrechnung() {
        assertThat(Werteformat.betrag(new BigDecimal("2500"), "EUR")).isEqualTo("EUR 2'500.00");
        assertThat(Werteformat.betrag(new BigDecimal("2500"), null)).isEqualTo("CHF 2'500.00");
    }

    @Test
    void datumUndZeitpunktSchweizerSchreibweise() {
        assertThat(Werteformat.datum(LocalDate.of(2026, 3, 1))).isEqualTo("01.03.2026");
        assertThat(Werteformat.datum(null)).isNull();
        // UTC 23:30 ist in Zürich (Winterzeit) schon der nächste Tag
        assertThat(Werteformat.zeitpunkt(Instant.parse("2026-11-14T23:30:00Z"))).isEqualTo("15.11.2026 00:30");
    }
}
