package ch.urinet.archiv.protokoll.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code details} nur mit Grund, SHA-256 und Fehlercode (Req 10.2). */
class ProtokollDetailsTest {

    @Test
    void sha256WirdKleinGeschriebenUndGeprueft() {
        assertThat(new ProtokollDetails(null, "AB".repeat(32), null).sha256()).isEqualTo("ab".repeat(32));
        assertThatThrownBy(() -> new ProtokollDetails(null, "Beleg 2026-003", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("2026-003");
    }

    @Test
    void fehlercodeNurAlsCode() {
        assertThat(ProtokollDetails.fehlercode("NICHT_ERLAUBT").fehlercode()).isEqualTo("NICHT_ERLAUBT");
        assertThatThrownBy(() -> ProtokollDetails.fehlercode("nicht erlaubt: Beleg"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("Beleg");
    }

    @Test
    void leererGrundGiltAlsFehlend() {
        assertThat(new ProtokollDetails(" ", null, null).leer()).isTrue();
        assertThat(ProtokollDetails.KEINE.leer()).isTrue();
        assertThat(new ProtokollDetails("A7", null, null).leer()).isFalse();
    }
}
