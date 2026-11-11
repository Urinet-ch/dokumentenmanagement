package ch.urinet.archiv.ablage.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** retainUntil nach design.md «Ablage» (Req 6.3, 6.9, 6.11, 6.12, 9.8). */
class SperrfristTest {

    private static final Instant JETZT = Instant.parse("2026-11-10T09:15:30.250Z");

    @Test
    void ohneGesetzlichePflichtKeineSperre() {
        assertThat(Sperrfrist.retainUntil(Sperrvorgabe.ohnePflicht(), JETZT, Optional.empty())).isEmpty();
    }

    @Test
    void abgelaufeneFristKeineSperreAuchBeiPflicht() {
        var vorgabe = new Sperrvorgabe(true, LocalDate.of(2036, 12, 31), true);
        assertThat(Sperrfrist.retainUntil(vorgabe, JETZT, Optional.empty())).isEmpty();
    }

    @Test
    void unbefristetZehnJahreAufSekundenAufgerundet() {
        assertThat(Sperrfrist.retainUntil(Sperrvorgabe.unbefristet(), JETZT, Optional.empty()))
                .contains(Instant.parse("2036-11-10T09:15:31Z"));
    }

    @Test
    void fristBisTagesendeInZuerichAlsUtc() {
        // Winterzeit: Ende des 31.12. in Zürich ist 23:00 UTC
        assertThat(Sperrfrist.retainUntil(Sperrvorgabe.bis(LocalDate.of(2036, 12, 31)), JETZT, Optional.empty()))
                .contains(Instant.parse("2036-12-31T23:00:00Z"));
        // Sommerzeit: 22:00 UTC
        assertThat(Sperrfrist.tagesende(LocalDate.of(2036, 6, 30))).isEqualTo(Instant.parse("2036-06-30T22:00:00Z"));
    }

    @Test
    void hoechstfristBegrenztAufStufeA() {
        assertThat(Sperrfrist.retainUntil(Sperrvorgabe.bis(LocalDate.of(2036, 12, 31)), JETZT,
                Optional.of(Duration.ofDays(1)))).contains(Instant.parse("2026-11-11T09:15:31Z"));
    }

    @Test
    void fristInDerVergangenheitKeineSperre() {
        assertThat(Sperrfrist.retainUntil(Sperrvorgabe.bis(LocalDate.of(2020, 1, 1)), JETZT, Optional.empty()))
                .isEmpty();
    }

    @Test
    void manifestBisSpaetestemDatumSonstZehnJahre() {
        assertThat(Sperrfrist.manifest(Optional.of(LocalDate.of(2037, 1, 1)), JETZT, Optional.empty()))
                .isEqualTo(Instant.parse("2037-01-01T23:00:00Z"));
        assertThat(Sperrfrist.manifest(Optional.empty(), JETZT, Optional.empty()))
                .isEqualTo(Instant.parse("2036-11-10T09:15:31Z"));
        assertThatThrownBy(() -> Sperrfrist.manifest(Optional.of(LocalDate.of(2020, 1, 1)), JETZT, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
