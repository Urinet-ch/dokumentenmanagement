package ch.urinet.archiv.ki.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Nachprüfung #176/#177: Randfälle der Beträge (Spec 02, Req 3.7). */
class WerteformatRandfaelleTest {

    @Test
    void beitragDerAufNullRundetHatKeinVorzeichen() {
        assertThat(Werteformat.chf(new BigDecimal("-0.004"))).isEqualTo("CHF 0.00");
        assertThat(Werteformat.chf(new BigDecimal("-0.00"))).isEqualTo("CHF 0.00");
        assertThat(Werteformat.chf(new BigDecimal("-0.005"))).isEqualTo("CHF -0.01");
    }

    @Test
    void grosseUndExponentschreibweise() {
        assertThat(Werteformat.chf(new BigDecimal("1E+3"))).isEqualTo("CHF 1'000.00");
        assertThat(Werteformat.chf(new BigDecimal("-1250.5"))).isEqualTo("CHF -1'250.50");
        assertThat(Werteformat.chf(new BigDecimal("100000"))).isEqualTo("CHF 100'000.00");
        assertThat(Werteformat.betrag(new BigDecimal("0.5"), " EUR ")).isEqualTo("EUR 0.50");
    }
}
