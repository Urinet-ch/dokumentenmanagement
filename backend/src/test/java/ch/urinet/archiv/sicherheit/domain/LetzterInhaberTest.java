package ch.urinet.archiv.sicherheit.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LetzterInhaberTest {

    @Test
    void blockiertNurDasEntfernenDesLetztenAktivenInhabers() {
        assertThat(Vergaberegeln.entferntLetztenAktivenInhaber(Rolle.INHABER, true, 1)).isTrue();
        assertThat(Vergaberegeln.entferntLetztenAktivenInhaber(Rolle.INHABER, true, 2)).isFalse();
        assertThat(Vergaberegeln.entferntLetztenAktivenInhaber(Rolle.INHABER, false, 1)).isFalse();
        assertThat(Vergaberegeln.entferntLetztenAktivenInhaber(Rolle.MITARBEITER, true, 1)).isFalse();
        assertThat(Vergaberegeln.entferntLetztenAktivenInhaber(Rolle.INHABER, true, 0)).isTrue();
    }

    @Test
    void lehntUngueltigeZaehlerAb() {
        assertThatThrownBy(() -> Vergaberegeln.entferntLetztenAktivenInhaber(Rolle.INHABER, true, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
