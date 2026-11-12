package ch.urinet.archiv.mandant.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MandantCodeTest {

    @Test
    void schemaUndBucketAusDemCode() {
        MandantCode code = new MandantCode("vhf-gsk");
        assertThat(code.schema()).isEqualTo("m_vhf_gsk");
        assertThat(code.bucket("urinet-archiv-")).isEqualTo("urinet-archiv-vhf-gsk");
    }

    @Test
    void praefixDerStufeA() {
        assertThat(new MandantCode("urinet").bucket("urinet-archiv-")).isEqualTo("urinet-archiv-urinet");
        assertThat(new MandantCode("urinet").bucket("urinet-archiv-a-")).isEqualTo("urinet-archiv-a-urinet");
    }

    @Test
    void codesMitAStrichSindFuerStufeAReserviert() {
        for (String code : new String[]{"a-urinet", "a-x"}) {
            assertThatThrownBy(() -> new MandantCode(code)).as(code).isInstanceOf(IllegalArgumentException.class);
        }
        for (String code : new String[]{"a", "ab-x", "aa-x", "x-a-y"}) {
            assertThat(new MandantCode(code).wert()).isEqualTo(code);
        }
    }

    @Test
    void codesMitPStrichSindFuerStufePReserviert() {
        for (String code : new String[]{"p-urinet", "p-x"}) {
            assertThatThrownBy(() -> new MandantCode(code)).as(code).isInstanceOf(IllegalArgumentException.class);
        }
        for (String code : new String[]{"p", "pp-x", "x-p-y"}) {
            assertThat(new MandantCode(code).wert()).isEqualTo(code);
        }
    }

    @Test
    void ungueltigeCodesUndBuckets() {
        for (String code : new String[]{"Urinet", "m_x", "-urinet", "urinet-", "a--b", "", "x".repeat(41)}) {
            assertThatThrownBy(() -> new MandantCode(code)).as(code).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new MandantCode("urinet").bucket("Urinet_")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MandantCode("a".repeat(40)).bucket("p".repeat(30) + "-"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
