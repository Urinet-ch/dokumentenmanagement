package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.wissen.adapter.out.zeitplan.QuartzWissenZeitplan;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WissenEinstellungenTest {
    private final WissenEinstellungen einstellungen = new WissenEinstellungen(null, null, new QuartzWissenZeitplan());

    @Test
    void startwerteUndQuartzZeitplanSindZulaessig() {
        assertThatCode(() -> einstellungen.pruefen(new WissenEinstellungen.Werte(null, null, null, null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> einstellungen.pruefen(
                new WissenEinstellungen.Werte(600000, 100000, "0 0 4 ? * SUN", 1000000)))
                .doesNotThrowAnyException();
    }

    @Test
    void ungueltigesBudgetUndUngueltigerZeitplanWerdenAbgelehnt() {
        assertThatThrownBy(() -> einstellungen.pruefen(new WissenEinstellungen.Werte(null, null, null, 0)))
                .isInstanceOf(FachFehler.class);
        assertThatThrownBy(() -> einstellungen.pruefen(
                new WissenEinstellungen.Werte(1, 1, "kein Zeitplan", 1))).isInstanceOf(FachFehler.class);
    }
}
