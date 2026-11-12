package ch.urinet.archiv.ablage.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Sperrpflege im Prüflauf (Req 15.9, 16.24). */
class SperrpflegeTest {

    private static final Instant JETZT = Instant.parse("2026-11-10T09:15:30Z");
    private static final Instant BALD = Instant.parse("2027-03-01T00:00:00Z");

    @Test
    void ohneOderMitAbgelaufenerSperreWirdNichtsGesetzt() {
        assertThat(Sperrpflege.entscheiden(Stufe.B, Optional.empty(), JETZT, Optional.empty()).art())
                .isEqualTo(Sperrpflege.Art.OHNE_SPERRE);
        assertThat(Sperrpflege.entscheiden(Stufe.B, Optional.of(JETZT), JETZT, Optional.empty()).art())
                .isEqualTo(Sperrpflege.Art.OHNE_SPERRE);
    }

    @Test
    void langeSperreReicht() {
        assertThat(Sperrpflege.entscheiden(Stufe.B, Optional.of(Instant.parse("2030-01-01T00:00:00Z")), JETZT,
                Optional.empty()).art()).isEqualTo(Sperrpflege.Art.AUSREICHEND);
    }

    @Test
    void stufeBVerlaengertUmZehnJahre() {
        assertThat(Sperrpflege.entscheiden(Stufe.B, Optional.of(BALD), JETZT, Optional.empty()))
                .isEqualTo(new Sperrpflege(Sperrpflege.Art.VERLAENGERN,
                        Optional.of(Instant.parse("2037-03-01T00:00:00Z"))));
    }

    @Test
    void stufePZaehltNurUndVerlaengertNie() {
        Sperrpflege pflege = Sperrpflege.entscheiden(Stufe.P, Optional.of(JETZT.plusSeconds(60)), JETZT,
                Optional.of(Duration.ofDays(1)));
        assertThat(pflege.art()).isEqualTo(Sperrpflege.Art.NUR_ZAEHLEN);
        assertThat(pflege.bis()).isEmpty();
    }

    @Test
    void stufeAGekapptAufEinenTag() {
        assertThat(Sperrpflege.entscheiden(Stufe.A, Optional.of(JETZT.plusSeconds(60)), JETZT,
                Optional.of(Duration.ofDays(1))).bis()).contains(JETZT.plus(Duration.ofDays(1)));
        assertThat(Sperrpflege.entscheiden(Stufe.A, Optional.of(JETZT.plus(Duration.ofDays(1))), JETZT,
                Optional.of(Duration.ofDays(1))).art()).isEqualTo(Sperrpflege.Art.GEKAPPT);
    }
}
