package ch.urinet.archiv.gemeinsam.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeitentextTest {

    @Test
    void ohneTrennerKeineSeiten() {
        assertThat(Seitentext.von("Text ohne Trenner")).isEmpty();
        assertThat(Seitentext.von(null)).isEmpty();
    }

    @Test
    void trennerNachDerLetztenSeiteErgibtKeineZusaetzlicheSeite() {
        assertThat(Seitentext.von("eins\fzwei\f").orElseThrow().seiten()).containsExactly("eins", "zwei");
        assertThat(Seitentext.von("eins\fzwei\f\n").orElseThrow().anzahl()).isEqualTo(2);
    }

    @Test
    void leereSeiteMittenImTextBleibt() {
        assertThat(Seitentext.von("eins\f\fdrei\f").orElseThrow().seiten()).containsExactly("eins", "", "drei");
    }

    @Test
    void seitenNurBeiPassenderZahl() {
        assertThat(Seitentext.von("eins\fzwei\f", 2)).isPresent();
        assertThat(Seitentext.von("eins\fzwei\f", 3)).isEmpty();
        assertThat(Seitentext.von("eins\fzwei\f", null)).isEmpty();
    }

    // Plausibilität der Fundstelle (Spec 03, Req 5.7): Seite liegt in 1 bis dokument.seiten

    @Test
    void seiteInnerhalbDesBereichsBleibtErhalten() {
        assertThat(Seitentext.plausibel(1, 12)).isEqualTo(1);
        assertThat(Seitentext.plausibel(7, 12)).isEqualTo(7);
        assertThat(Seitentext.plausibel(12, 12)).isEqualTo(12);
    }

    @Test
    void seiteAusserhalbDesBereichsFaelltWeg() {
        assertThat(Seitentext.plausibel(0, 12)).isNull();
        assertThat(Seitentext.plausibel(-1, 12)).isNull();
        assertThat(Seitentext.plausibel(13, 12)).isNull();
    }

    @Test
    void ohneSeiteOderOhneSeitenzahlKeineFundstelle() {
        assertThat(Seitentext.plausibel(null, 12)).isNull();
        assertThat(Seitentext.plausibel(5, null)).isNull();
        assertThat(Seitentext.plausibel(null, null)).isNull();
        assertThat(Seitentext.plausibel(1, 0)).isNull();
    }
}
