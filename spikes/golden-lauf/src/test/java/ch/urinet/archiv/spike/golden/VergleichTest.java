package ch.urinet.archiv.spike.golden;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Req 0.7 und Normalisierung nach design.md. */
class VergleichTest {

    @Test
    void normalisierungUmlauteRechtsformenUndZeichen() {
        assertThat(Normalisierung.name("Druckerei Zgraggen & Arnold AG")).isEqualTo("druckerei zgraggen und arnold");
        assertThat(Normalisierung.name("Bäckerei-Café Tresch")).isEqualTo("baeckerei cafe tresch");
        assertThat(Normalisierung.name("Alpwerk Photographie Sàrl")).isEqualTo("alpwerk photographie");
        assertThat(Normalisierung.name("  Grüezi   Strasse GmbH ")).isEqualTo("grueezi strasse");
        assertThat(Normalisierung.name("Weiß Verein")).isEqualTo("weiss");
        assertThat(Normalisierung.name("Calcolo Leventina SA")).isEqualTo("calcolo leventina");
        assertThat(Normalisierung.name("Sagl-Werk")).isEqualTo("werk");
    }

    @Test
    void artUndDatumExakt() {
        assertThat(Vergleich.art("QUITTUNG", "QUITTUNG")).isTrue();
        assertThat(Vergleich.art("QUITTUNG", "LIEFERANTENBELEG")).isFalse();
        assertThat(Vergleich.datum(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1))).isTrue();
        assertThat(Vergleich.datum(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 1, 3))).isFalse();
    }

    @Test
    void betragAufDenRappen() {
        assertThat(Vergleich.betrag(new BigDecimal("120.50"), new BigDecimal("120.5"))).isTrue();
        assertThat(Vergleich.betrag(new BigDecimal("-120.00"), new BigDecimal("-120"))).isTrue();
        assertThat(Vergleich.betrag(new BigDecimal("120.50"), new BigDecimal("120.55"))).isFalse();
    }

    @Test
    void partnerNormalisiertUndBelegnummerOhneLeerzeichen() {
        assertThat(Vergleich.partner("Bergnetz Hosting AG", "bergnetz hosting")).isTrue();
        assertThat(Vergleich.partner("Bergnetz Hosting AG", "Bergnetz")).isFalse();
        assertThat(Vergleich.belegnummer("RE-55120", "RE - 55120")).isTrue();
        assertThat(Vergleich.belegnummer("2026-123", "2026-124")).isFalse();
    }

    @Test
    void erwartetNullNurMitLeeremVorschlagRichtig() {
        assertThat(Vergleich.belegnummer(null, null)).isTrue();
        assertThat(Vergleich.belegnummer(null, "  ")).isTrue();
        assertThat(Vergleich.belegnummer(null, "4711")).isFalse();
        assertThat(Vergleich.betrag(null, null)).isTrue();
        assertThat(Vergleich.betrag(null, BigDecimal.ZERO)).isFalse();
        assertThat(Vergleich.datum(null, null)).isTrue();
        assertThat(Vergleich.datum(null, LocalDate.of(2026, 1, 1))).isFalse();
        assertThat(Vergleich.partner(null, "x")).isFalse();
        assertThat(Vergleich.betrag(BigDecimal.ONE, null)).isFalse();
    }
}
