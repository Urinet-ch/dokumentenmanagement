package ch.urinet.archiv.ablage.domain;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SperrplanTest {

    private static final Instant JETZT = Instant.parse("2026-11-15T12:00:00Z");

    @Test
    @Tag("req-16.27")
    void ohneKappungBleibtDieIstSperreUnveraendert() {
        Sperrplan plan = Sperrplan.berechnen(Optional.of(new SicherungsSperre(
                "GOVERNANCE", Instant.parse("2036-11-15T12:00:00Z"))), false, JETZT);

        assertThat(plan.modus()).contains(Sperrmodus.GOVERNANCE);
        assertThat(plan.bis()).contains(Instant.parse("2036-11-15T12:00:00Z"));
        assertThat(plan.gekappt()).isFalse();
    }

    @Test
    @Tag("req-16.27")
    void kappenNimmtDasFruehereAusIstSperreUndMorgen() {
        Sperrplan heute = Sperrplan.berechnen(Optional.of(new SicherungsSperre(
                "GOVERNANCE", JETZT)), true, JETZT);
        Sperrplan morgen = Sperrplan.berechnen(Optional.of(new SicherungsSperre(
                "GOVERNANCE", JETZT.plusSeconds(86_400))), true, JETZT);
        Sperrplan spaeter = Sperrplan.berechnen(Optional.of(new SicherungsSperre(
                "COMPLIANCE", Instant.parse("2036-11-15T12:00:00Z"))), true, JETZT);

        assertThat(heute.modus()).isEmpty();
        assertThat(morgen.bis()).contains(JETZT.plusSeconds(86_400));
        assertThat(morgen.gekappt()).isFalse();
        assertThat(spaeter.bis()).contains(JETZT.plusSeconds(86_400));
        assertThat(spaeter.modus()).contains(Sperrmodus.COMPLIANCE);
        assertThat(spaeter.gekappt()).isTrue();
    }

    @Test
    @Tag("req-16.27")
    void fehlendeUndAbgelaufeneIstSperrenWerdenAlsOhneSperreBehandelt() {
        assertThat(Sperrplan.berechnen(Optional.empty(), false, JETZT).bis()).isEmpty();
        assertThat(Sperrplan.berechnen(Optional.of(new SicherungsSperre(
                "GOVERNANCE", JETZT.minusSeconds(1))), false, JETZT).modus()).isEmpty();
    }
}
