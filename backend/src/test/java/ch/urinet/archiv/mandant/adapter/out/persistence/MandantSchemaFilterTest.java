package ch.urinet.archiv.mandant.adapter.out.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Beim Start prüft Hibernate nur {@code public}, nach der Migration je Mandant nur dessen Schema (Req 1.7). */
class MandantSchemaFilterTest {

    @Test
    void ohneMandantNurQualifizierteTabellen() {
        assertThat(MandantSchemaFilter.einbeziehen("public", null)).isTrue();
        assertThat(MandantSchemaFilter.einbeziehen(null, null)).isFalse();
    }

    @Test
    void mitMandantNurDessenSchema() {
        assertThat(MandantSchemaFilter.einbeziehen(null, "m_urinet")).isTrue();
        assertThat(MandantSchemaFilter.einbeziehen("m_urinet", "m_urinet")).isTrue();
        assertThat(MandantSchemaFilter.einbeziehen("public", "m_urinet")).isFalse();
    }
}
