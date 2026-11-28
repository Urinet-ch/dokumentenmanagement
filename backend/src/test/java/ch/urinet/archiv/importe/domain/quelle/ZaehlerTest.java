package ch.urinet.archiv.importe.domain.quelle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Zähler eines Quelllaufs gehen auf (Spec 09, 10.3, 10.5) und Quellen setzen die Quelle des Dokuments (10.4). */
class ZaehlerTest {

    @Test
    void zaehlerGehenAuf() {
        assertThat(new Zaehler(5, 2, 1, 1, 1, 1).gehtAuf()).isTrue();
        assertThat(new Zaehler(5, 2, 1, 0, 1, 0).gehtAuf()).isFalse();
        assertThat(new Zaehler(1, 1, 0, 2, 0, 0).gehtAuf()).isFalse();
        assertThat(Zaehler.NULL.gehtAuf()).isTrue();
        assertThatThrownBy(() -> new Zaehler(-1, 0, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trockenlaufOhneFachlicheDubletten() {
        assertThat(new Zaehler(3, 2, 1, 0, 0, 0).gueltigFuer(LaufArt.TROCKEN)).isTrue();
        assertThat(new Zaehler(3, 2, 1, 1, 0, 0).gueltigFuer(LaufArt.TROCKEN)).isFalse();
        assertThat(new Zaehler(3, 2, 1, 1, 0, 0).gueltigFuer(LaufArt.ECHT)).isTrue();
    }

    @Test
    void quelleDesDokuments() {
        assertThat(QuellenTyp.MAC_EINGANG.dokumentQuelle()).contains("ORDNER");
        assertThat(QuellenTyp.ORDNER.dokumentQuelle()).contains("ORDNER");
        assertThat(QuellenTyp.GMAIL_LABEL.dokumentQuelle()).contains("GMAIL");
        assertThat(QuellenTyp.DRIVE.dokumentQuelle()).isEmpty();
        assertThat(Quelle.codeGueltig("mac-ordner")).isTrue();
        assertThat(Quelle.codeGueltig("Mac Ordner")).isFalse();
    }
}
