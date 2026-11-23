package ch.urinet.archiv.benutzer.adapter.out.stepup;

import ch.urinet.archiv.gemeinsam.domain.StepUpNoetig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepUpMitAuthTimeTest {
    private static final Instant JETZT = Instant.parse("2026-11-22T12:00:00Z");
    private static final Duration GUELTIGKEIT = Duration.ofMinutes(5);

    @Test
    void frischeAnmeldungUndExakteGrenzeSindGueltig() {
        assertThatCode(() -> StepUpMitAuthTime.pruefenAuthTime(JETZT.getEpochSecond(), GUELTIGKEIT, JETZT))
                .doesNotThrowAnyException();
        assertThatCode(() -> StepUpMitAuthTime.pruefenAuthTime(
                JETZT.minus(GUELTIGKEIT).getEpochSecond(), GUELTIGKEIT, JETZT))
                .doesNotThrowAnyException();
    }

    @Test
    void fehlenderUngueltigerUndVeralteterClaimFordernErneuteAnmeldung() {
        for (Object claim : new Object[]{null, "ungültig", new BigDecimal("1763812500.5"), Long.MAX_VALUE,
                JETZT.minus(GUELTIGKEIT).minusSeconds(1).getEpochSecond()}) {
            assertThatThrownBy(() -> StepUpMitAuthTime.pruefenAuthTime(claim, GUELTIGKEIT, JETZT))
                    .isInstanceOfSatisfying(StepUpNoetig.class,
                            fehler -> org.assertj.core.api.Assertions.assertThat(fehler.maxAlterSekunden())
                                    .isEqualTo(GUELTIGKEIT.toSeconds()));
        }
    }

    @Test
    void zukunftsdatumUndUngueltigeGueltigkeitWerdenAbgewiesen() {
        assertThatThrownBy(() -> StepUpMitAuthTime.pruefenAuthTime(
                JETZT.plusSeconds(1).getEpochSecond(), GUELTIGKEIT, JETZT))
                .isInstanceOf(StepUpNoetig.class);
        for (Duration ungueltig : new Duration[]{Duration.ZERO, Duration.ofNanos(1),
                Duration.ofMinutes(15).plusSeconds(1)}) {
            assertThatThrownBy(() -> StepUpMitAuthTime.pruefenAuthTime(JETZT.getEpochSecond(), ungueltig, JETZT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anmeldungDieGenauAmGrenzwertAblaeuftIstNichtMehrGueltig() {
        Instant knappZuSpaet = JETZT.plusNanos(1);
        assertThatThrownBy(() -> StepUpMitAuthTime.pruefenAuthTime(
                JETZT.minus(GUELTIGKEIT).getEpochSecond(), GUELTIGKEIT, knappZuSpaet))
                .isInstanceOf(StepUpNoetig.class);
    }
}
