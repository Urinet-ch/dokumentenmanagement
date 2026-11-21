package ch.urinet.archiv.benutzer.adapter.out.stepup;

import ch.urinet.archiv.gemeinsam.domain.StepUpNoetig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepUpAusstehendTest {
    @Test
    void ohneImplementierungWirdDieAktionNichtFreigegeben() {
        assertThatThrownBy(() -> new StepUpAusstehend().pruefen())
                .isInstanceOfSatisfying(StepUpNoetig.class,
                        fehler -> assertThat(fehler.maxAlterSekunden()).isEqualTo(300));
    }
}
