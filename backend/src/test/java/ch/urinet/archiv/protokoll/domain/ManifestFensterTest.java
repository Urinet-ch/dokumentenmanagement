package ch.urinet.archiv.protokoll.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fenster des Tages-Manifests in Europe/Zurich (Req 15.7). */
class ManifestFensterTest {

    @Test
    void umstellungstageHaben23Bzw25Stunden() {
        LocalDate sommer = LocalDate.of(2026, 3, 29);
        LocalDate winter = LocalDate.of(2026, 10, 25);
        assertThat(dauer(sommer)).isEqualTo(Duration.ofHours(23));
        assertThat(dauer(winter)).isEqualTo(Duration.ofHours(25));
        assertThat(dauer(LocalDate.of(2026, 1, 10))).isEqualTo(Duration.ofHours(24));
        assertThat(ManifestFenster.bis(winter)).isEqualTo(Instant.parse("2026-10-25T23:00:00Z"));
    }

    @Test
    void dieWiederholteStundeDerWinterzeitGehoertZumSelbenTag() {
        assertThat(ManifestFenster.tag(Instant.parse("2026-10-25T00:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(ManifestFenster.tag(Instant.parse("2026-10-25T01:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(ManifestFenster.tag(Instant.parse("2026-10-25T22:59:59Z"))).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(ManifestFenster.tag(Instant.parse("2026-10-25T23:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 26));
    }

    @Test
    void fensterSchliessenNahtlosAnUndPruefenIhreGrenzen() {
        LocalDate tag = LocalDate.of(2026, 1, 10);
        ManifestFenster erstes = ManifestFenster.fuer(tag, null);
        ManifestFenster zweites = ManifestFenster.fuer(tag.plusDays(1), erstes.bis());
        assertThat(erstes.erstes()).isTrue();
        assertThat(zweites.erstes()).isFalse();
        assertThat(zweites.von()).isEqualTo(erstes.bis());
        assertThatThrownBy(() -> new ManifestFenster(tag, null, erstes.bis().minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManifestFenster.fuer(tag, erstes.bis()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("von liegt nicht vor bis");
    }

    private static Duration dauer(LocalDate datum) {
        return Duration.between(ManifestFenster.bis(datum.minusDays(1)), ManifestFenster.bis(datum));
    }
}
