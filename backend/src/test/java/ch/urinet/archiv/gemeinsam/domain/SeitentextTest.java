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
}
