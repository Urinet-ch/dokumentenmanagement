package ch.urinet.archiv.integration.application.webseite;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Aufbewahrung des Feeds: 1 bis 365 Tage, sonst Startabbruch (Spec 03, Aufgabe 11.9, Req 14.4). */
class AenderungFeedAufraeumenTest {

    @Test
    void dieGrenzeLiegtNeunzigTageZurueck() {
        AenderungFeedAufraeumen a = new AenderungFeedAufraeumen(null, null, Duration.parse("P90D"));

        assertThat(a.grenze(Instant.parse("2026-11-16T03:50:00Z"))).isEqualTo(Instant.parse("2026-08-18T03:50:00Z"));
    }

    @Test
    void werteAusserhalbVonEinsBis365TagenBrechenDenStartAb() {
        for (String wert : new String[]{"PT0S", "PT23H", "P366D", "P1000D"}) {
            assertThatThrownBy(() -> new AenderungFeedAufraeumen(null, null, Duration.parse(wert))).as(wert)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("archiv.webseite.feed-aufbewahrung");
        }
        new AenderungFeedAufraeumen(null, null, Duration.parse("P1D"));
        new AenderungFeedAufraeumen(null, null, Duration.parse("P365D"));
    }
}
