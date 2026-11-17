package ch.urinet.archiv.konfiguration.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BelegnummerMusterTest {

    @Test
    void erlaubtGueltigeJdkMuster() {
        assertThat(BelegnummerMuster.gueltig("^(?<jahr>\\d{4})-(?<nr>\\d{3})$")).isTrue();
        assertThat(BelegnummerMuster.gueltig("^R\\d{4}$")).isTrue();
        assertThat(BelegnummerMuster.gueltig("(?=a)(a+)\\1")).isTrue();
    }

    @Test
    void weistUngueltigeOderZuLangeMusterAb() {
        assertThat(BelegnummerMuster.gueltig("(")).isFalse();
        assertThat(BelegnummerMuster.gueltig("a".repeat(BelegnummerMuster.MAX_ZEICHEN + 1))).isFalse();
    }
}
