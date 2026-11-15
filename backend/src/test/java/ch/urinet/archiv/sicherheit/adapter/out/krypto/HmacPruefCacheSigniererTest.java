package ch.urinet.archiv.sicherheit.adapter.out.krypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacPruefCacheSigniererTest {

    @Test
    void signaturIstStabilUndGeheimnisgebunden() {
        HmacPruefCacheSignierer signierer = new HmacPruefCacheSignierer();

        assertThat(signierer.signieren("geheim")).containsExactly(signierer.signieren("geheim"));
        assertThat(signierer.signieren("anders")).isNotEqualTo(signierer.signieren("geheim"));
    }

    @Test
    void schluesselIstProInstanzVerschieden() {
        HmacPruefCacheSignierer erster = new HmacPruefCacheSignierer();
        HmacPruefCacheSignierer zweiter = new HmacPruefCacheSignierer();

        assertThat(erster.signieren("geheim")).isNotEqualTo(zweiter.signieren("geheim"));
    }
}
