package ch.urinet.archiv.ki.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Gültigkeit der Antwort des Rerankers (Spec 02, Aufgabe 6.4): Bereich, Doppel, Zahl. */
class NeuordnungTest {

    @Test
    void gueltigMitGenauTopNVerschiedenenIndizesImBereich() {
        assertThat(Neuordnung.gueltig(List.of(2, 0, 1), 3, 3)).isTrue();
        assertThat(Neuordnung.gueltig(List.of(4, 1), 5, 2)).isTrue();
    }

    @Test
    void indexAusserhalbIstUngueltig() {
        assertThat(Neuordnung.gueltig(List.of(0, 3), 3, 2)).isFalse();
        assertThat(Neuordnung.gueltig(List.of(-1, 0), 3, 2)).isFalse();
        assertThat(Neuordnung.gueltig(Arrays.asList(0, null), 3, 2)).isFalse();
    }

    @Test
    void doppelterIndexIstUngueltig() {
        assertThat(Neuordnung.gueltig(List.of(1, 1, 0), 3, 3)).isFalse();
    }

    @Test
    void zuWenigeOderZuVieleSindUngueltig() {
        assertThat(Neuordnung.gueltig(List.of(0, 1), 3, 3)).isFalse();
        assertThat(Neuordnung.gueltig(List.of(0, 1, 2), 3, 2)).isFalse();
        assertThat(Neuordnung.gueltig(List.of(), 3, 1)).isFalse();
        assertThat(Neuordnung.gueltig(null, 3, 1)).isFalse();
    }
}
