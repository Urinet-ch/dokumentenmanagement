package ch.urinet.archiv.suche.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Seitenangabe der Chat-Quellen, plausibel gegen {@code dokument.seiten} (Spec 03, Req 5.7). */
class FundstelleTest {

    @Test
    void seitenImDokumentWerdenGenannt() {
        assertThat(new Fundstelle(1, 2, 3).seitenText(12)).isEqualTo("2-3");
        assertThat(new Fundstelle(1, 2, 2).seitenText(12)).isEqualTo("2");
        assertThat(new Fundstelle(1, 2, null).seitenText(12)).isEqualTo("2");
        assertThat(new Fundstelle(1, 1, 12).seitenText(12)).isEqualTo("1-12");
    }

    @Test
    void ersteSeiteAusserhalbLaesstDieAngabeWeg() {
        assertThat(new Fundstelle(1, 0, 1).seitenText(12)).isNull();
        assertThat(new Fundstelle(1, -1, 2).seitenText(12)).isNull();
        assertThat(new Fundstelle(1, 13, 14).seitenText(12)).isNull();
    }

    @Test
    void letzteSeiteAusserhalbLaesstNurDieErsteStehen() {
        assertThat(new Fundstelle(1, 12, 13).seitenText(12)).isEqualTo("12");
    }

    @Test
    void ohneSeitenzahlOderSeiteKeineAngabe() {
        assertThat(new Fundstelle(1, 2, 3).seitenText(null)).isNull();
        assertThat(new Fundstelle(1, 2, 3).seitenText(0)).isNull();
        assertThat(new Fundstelle(1, null, null).seitenText(12)).isNull();
    }
}
