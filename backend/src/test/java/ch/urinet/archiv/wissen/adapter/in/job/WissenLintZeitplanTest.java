package ch.urinet.archiv.wissen.adapter.in.job;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WissenLintZeitplanTest {
    @Test
    void sonntagVierUhrNachZuercherZeitMitSommerUndWinterzeit() {
        String sonntag = "0 0 4 ? * SUN";
        assertThat(WissenLintZeitplan.faellig(sonntag, Instant.parse("2026-01-04T03:00:20Z")))
                .contains(Instant.parse("2026-01-04T03:00:00Z"));
        assertThat(WissenLintZeitplan.faellig(sonntag, Instant.parse("2026-07-05T02:00:20Z")))
                .contains(Instant.parse("2026-07-05T02:00:00Z"));
        assertThat(WissenLintZeitplan.faellig(sonntag, Instant.parse("2026-07-05T03:00:20Z"))).isEmpty();
        assertThat(WissenLintZeitplan.faellig("0 30 9 ? * MON", Instant.parse("2026-07-06T07:30:01Z")))
                .contains(Instant.parse("2026-07-06T07:30:00Z"));
    }
}
