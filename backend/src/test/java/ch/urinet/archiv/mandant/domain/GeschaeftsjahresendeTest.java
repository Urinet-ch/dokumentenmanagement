package ch.urinet.archiv.mandant.domain;

import org.junit.jupiter.api.Test;

import java.time.MonthDay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeschaeftsjahresendeTest {

    @Test
    void gueltigeWerte() {
        assertThat(Geschaeftsjahresende.STANDARD.monatTag()).isEqualTo(MonthDay.of(12, 31));
        assertThat(new Geschaeftsjahresende("06-30").monatTag()).isEqualTo(MonthDay.of(6, 30));
        assertThat(new Geschaeftsjahresende("02-29").monatTag()).isEqualTo(MonthDay.of(2, 29));
    }

    @Test
    void ungueltigeWerte() {
        for (String wert : new String[]{"02-30", "13-01", "00-10", "04-31", "1231", "12-31 ", "6-30", null}) {
            assertThatThrownBy(() -> new Geschaeftsjahresende(wert)).as(String.valueOf(wert))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
