package ch.urinet.archiv.integration.domain.webseite;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 03, Req 15.1: Token-Bucket mit Spitze und Auffüllrate, Zeit nur als Argument. */
class TokenBucketTest {

    private static final Instant T0 = Instant.parse("2071-03-01T10:00:00Z");

    private static void nehmen(TokenBucket b, Instant t, int anzahl) {
        for (int i = 0; i < anzahl; i++) {
            assertThat(b.wartezeitSekunden(t)).isZero();
            b.nehmen();
        }
    }

    @Test
    void dieSpitzeErlaubtEinenStossDanachIstDerBucketLeer() {
        TokenBucket b = new TokenBucket(5, 1, T0);
        nehmen(b, T0, 5);
        assertThat(b.wartezeitSekunden(T0)).isEqualTo(1);
    }

    @Test
    void ProSekundeKommtDieEingestellteRateDazuBisZurSpitze() {
        TokenBucket b = new TokenBucket(5, 2, T0);
        nehmen(b, T0, 5);
        Instant t1 = T0.plusSeconds(1);
        nehmen(b, t1, 2);
        assertThat(b.wartezeitSekunden(t1)).isEqualTo(1);
        // nach langer Pause nie mehr als die Spitze
        Instant spaeter = T0.plusSeconds(3600);
        nehmen(b, spaeter, 5);
        assertThat(b.wartezeitSekunden(spaeter)).isPositive();
    }

    @Test
    void dieWartezeitIstAufgerundetUndLangsameRatenGebenLangeWartezeit() {
        TokenBucket b = new TokenBucket(1, 1 / 30.0, T0);
        nehmen(b, T0, 1);
        assertThat(b.wartezeitSekunden(T0)).isEqualTo(30);
        assertThat(b.wartezeitSekunden(T0.plusSeconds(10))).isEqualTo(20);
        assertThat(b.wartezeitSekunden(T0.plusMillis(29_500))).isEqualTo(1);
    }

    @Test
    void eineRueckwaertsLaufendeUhrFuelltNichtAufUndVerliertNichts() {
        TokenBucket b = new TokenBucket(2, 1, T0);
        nehmen(b, T0, 2);
        assertThat(b.wartezeitSekunden(T0.minusSeconds(100))).isEqualTo(1);
        assertThat(b.wartezeitSekunden(T0.plusSeconds(1))).isZero();
    }

    @Test
    void vollMeldetDenLeerlauf() {
        TokenBucket b = new TokenBucket(2, 1, T0);
        assertThat(b.voll(T0)).isTrue();
        nehmen(b, T0, 1);
        assertThat(b.voll(T0)).isFalse();
        assertThat(b.voll(T0.plusSeconds(1))).isTrue();
    }

    @Test
    void ungueltigeWerteWerdenAbgelehnt() {
        assertThatThrownBy(() -> new TokenBucket(0.5, 1, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(1, 0, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(Double.NaN, 1, T0)).isInstanceOf(IllegalArgumentException.class);
    }
}
