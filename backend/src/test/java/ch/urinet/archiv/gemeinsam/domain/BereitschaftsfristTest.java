package ch.urinet.archiv.gemeinsam.domain;

import ch.urinet.archiv.gemeinsam.domain.Bereitschaftsfrist.Lage;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Selbstheilung der Readiness (entschieden von Fabian am 11.11.2026, PR #42 P42-1). */
class BereitschaftsfristTest {

    private static final long S = 1_000_000_000L;
    private final Bereitschaftsfrist frist = new Bereitschaftsfrist(Duration.ofMinutes(5));

    @Test
    void beendetErstWennDieFristDurchgehendNichtBereitWar() {
        long start = 7 * S;
        assertThat(frist.melden(false, start)).isEqualTo(Lage.WARTEN);
        assertThat(frist.melden(false, start + 299 * S)).isEqualTo(Lage.WARTEN);
        assertThat(frist.melden(false, start + 300 * S)).isEqualTo(Lage.BEENDEN);
        assertThat(frist.zaehler()).isEqualTo(3);
        assertThat(frist.dauer(start + 300 * S)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void bereitSetztZaehlerUndBeginnZurueck() {
        frist.melden(false, 0);
        frist.melden(false, 290 * S);
        assertThat(frist.melden(true, 295 * S)).isEqualTo(Lage.BEREIT);
        assertThat(frist.zaehler()).isZero();
        assertThat(frist.dauer(295 * S)).isEqualTo(Duration.ZERO);
        // die Frist beginnt neu: 300 s nach der ersten Meldung zählt nicht mehr
        assertThat(frist.melden(false, 300 * S)).isEqualTo(Lage.WARTEN);
        assertThat(frist.melden(false, 599 * S)).isEqualTo(Lage.WARTEN);
        assertThat(frist.melden(false, 600 * S)).isEqualTo(Lage.BEENDEN);
    }

    @Test
    void negativeMonotoneZeitZaehltAlsAbstand() {
        // System.nanoTime() darf negativ sein; nur die Differenz zählt
        frist.melden(false, Long.MIN_VALUE + S);
        assertThat(frist.melden(false, Long.MIN_VALUE + 301 * S)).isEqualTo(Lage.BEENDEN);
    }

    @Test
    void fristUnterEinerMinuteIstUnzulaessig() {
        assertThatThrownBy(() -> new Bereitschaftsfrist(Duration.ofSeconds(59)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bereitschaftsfrist(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Bereitschaftsfrist(Duration.ofMinutes(1)).frist()).isEqualTo(Duration.ofMinutes(1));
    }
}
