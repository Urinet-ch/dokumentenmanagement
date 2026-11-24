package ch.urinet.archiv.konfiguration.domain;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WissenszeitplanTest {
    @Test
    void wochenplanWirdVerlustfreiAbgebildet() {
        var plan = new Konfiguration.LintZeitplan(DayOfWeek.MONDAY, 23);
        assertThat(Wissenszeitplan.lesen("0 0 23 ? * mon")).contains(plan);
        assertThat(Wissenszeitplan.cron(plan, "0 0 4 ? * SUN")).isEqualTo("0 0 23 ? * MON");
        assertThat(Wissenszeitplan.cron(new Konfiguration.LintZeitplan(null, null), "0 0 4 ? * SUN")).isNull();
        assertThat(Wissenszeitplan.cron(new Konfiguration.LintZeitplan(DayOfWeek.MONDAY, null), "0 0 9 ? * SUN"))
                .isEqualTo("0 0 9 ? * MON");
    }

    @Test
    void nichtDarstellbarePlaeneWerdenAbgelehnt() {
        assertThat(Wissenszeitplan.lesen(null)).isEmpty();
        assertThat(Wissenszeitplan.lesen("0 0/15 * * * ?")).isEmpty();
        assertThat(Wissenszeitplan.lesen("0 0 24 ? * SUN")).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() ->
                Wissenszeitplan.cron(new Konfiguration.LintZeitplan(DayOfWeek.SUNDAY, 24), "0 0 4 ? * SUN"));
    }
}
