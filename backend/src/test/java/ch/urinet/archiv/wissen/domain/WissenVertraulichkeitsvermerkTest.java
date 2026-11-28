package ch.urinet.archiv.wissen.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WissenVertraulichkeitsvermerkTest {

    @Test
    void erkenntExpliziteInterneVermerkeAlsEigeneZeile() {
        assertThat(WissenVertraulichkeitsvermerk.istIntern("INTERN\nText\f"))
                .isTrue();
        assertThat(WissenVertraulichkeitsvermerk.istIntern("Vertraulichkeit: nicht für Dritte\nText"))
                .isTrue();
        assertThat(WissenVertraulichkeitsvermerk.istIntern("Vertraulichkeitsvermerk = intern"))
                .isTrue();
    }

    @Test
    void erkenntPraefixUndVermerkUnabhaengigVonGrossschreibung() {
        assertThat(WissenVertraulichkeitsvermerk.istIntern("inTeRn"))
                .isTrue();
        assertThat(WissenVertraulichkeitsvermerk.istIntern("Vertraulichkeit: INTERN"))
                .isTrue();
        assertThat(WissenVertraulichkeitsvermerk.istIntern("vertraulichkeit: intern"))
                .isTrue();
    }

    @Test
    void stuftGewoehnlicheErwaehnungenNichtAlsVermerkEin() {
        assertThat(WissenVertraulichkeitsvermerk.istIntern("Wir arbeiten intern an diesem Vorhaben."))
                .isFalse();
        assertThat(WissenVertraulichkeitsvermerk.istIntern("Dieser Abschnitt ist nicht für Dritte gedacht."))
                .isFalse();
        assertThat(WissenVertraulichkeitsvermerk.istIntern(
                "Der Hinweis Vertraulichkeit: intern steht hier mitten im Satz."))
                .isFalse();
        assertThat(WissenVertraulichkeitsvermerk.istIntern(null)).isFalse();
    }
}
