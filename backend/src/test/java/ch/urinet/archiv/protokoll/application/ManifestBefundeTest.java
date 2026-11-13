package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regel der Manifest-Überwachung (Req 15.8, 16.5): erster fälliger Tag ohne Manifest, Frist streng, Auftrag in FEHLER. */
class ManifestBefundeTest {

    private static final Duration FRIST = Duration.ofHours(6);
    private static final LocalDate TAG = LocalDate.of(2026, 1, 10);

    private static Instant zuerich(int jahr, int monat, int tag, int stunde, int minute, int sekunde) {
        return LocalDateTime.of(jahr, monat, tag, stunde, minute, sekunde).atZone(ManifestFenster.ZUERICH).toInstant();
    }

    private static Optional<Befund> befund(LocalDate faellig, Optional<Instant> fortschritt, Optional<String> auftrag,
                                           Instant jetzt) {
        return ManifestBefunde.befund(faellig, fortschritt, auftrag, FRIST, jetzt);
    }

    @Test
    void derBefundNenntDatumUndStatus() {
        assertThat(befund(TAG, Optional.empty(), Optional.empty(), zuerich(2026, 11, 13, 9, 0, 0)))
                .contains(new Befund(Befund.Quelle.MANIFEST, "2026-01-10", "FEHLT"));
    }

    @Test
    void dieFristIstStreng() {
        Instant grenze = ManifestFenster.bis(TAG).plus(FRIST);
        assertThat(befund(TAG, Optional.empty(), Optional.empty(), grenze)).isEmpty();
        assertThat(befund(TAG, Optional.empty(), Optional.empty(), grenze.plusNanos(1))).isPresent();
    }

    @Test
    void fortschrittInnerhalbDerFristMeldetNichts() {
        Instant jetzt = zuerich(2026, 11, 13, 9, 0, 0);
        assertThat(befund(TAG, Optional.of(jetzt.minus(Duration.ofHours(5))), Optional.empty(), jetzt)).isEmpty();
        assertThat(befund(TAG, Optional.of(jetzt.minus(FRIST)), Optional.empty(), jetzt)).isEmpty();
        assertThat(befund(TAG, Optional.of(jetzt.minus(FRIST).minusSeconds(1)), Optional.empty(), jetzt)).isPresent();
    }

    @Test
    void dieFristBeginntAmEndeDesTagesInZuerichAuchBeiUmstellung() {
        // 29.03.2026 hat 23 Stunden, 25.10.2026 hat 25 Stunden; das Ende ist jeweils Mitternacht in Zürich
        LocalDate fruehling = LocalDate.of(2026, 3, 29);
        assertThat(befund(fruehling, Optional.empty(), Optional.empty(), zuerich(2026, 3, 30, 6, 0, 0))).isEmpty();
        assertThat(befund(fruehling, Optional.empty(), Optional.empty(), zuerich(2026, 3, 30, 6, 0, 1))).isPresent();
        LocalDate herbst = LocalDate.of(2026, 10, 25);
        assertThat(befund(herbst, Optional.empty(), Optional.empty(), zuerich(2026, 10, 26, 6, 0, 0))).isEmpty();
        assertThat(befund(herbst, Optional.empty(), Optional.empty(), zuerich(2026, 10, 26, 6, 0, 1))).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OFFEN", "LAEUFT", "FERTIG"})
    void einLaufenderOderFertigerAuftragMeldetInnerhalbDerFristNichts(String status) {
        Instant jetzt = zuerich(2026, 1, 11, 4, 0, 0);
        assertThat(befund(TAG, Optional.empty(), Optional.of(status), jetzt)).isEmpty();
    }

    @Test
    void einAuftragInFehlerMeldetSofort() {
        Instant jetzt = zuerich(2026, 1, 11, 4, 0, 0);
        assertThat(befund(TAG, Optional.of(jetzt), Optional.of("FEHLER"), jetzt))
                .contains(new Befund(Befund.Quelle.MANIFEST, "2026-01-10", "FEHLT"));
    }

    @Test
    void einNochNichtFaelligerTagMeldetAuchBeiFehlerNichts() {
        // gestern ist erst 15 Minuten nach Mitternacht fällig (ManifestErzeugen.NACHLAUF)
        assertThat(befund(TAG, Optional.empty(), Optional.of("FEHLER"), zuerich(2026, 1, 11, 0, 10, 0))).isEmpty();
        assertThat(befund(TAG, Optional.empty(), Optional.of("FEHLER"), zuerich(2026, 1, 11, 0, 20, 0))).isPresent();
        assertThat(befund(TAG.plusDays(1), Optional.empty(), Optional.of("FEHLER"), zuerich(2026, 1, 11, 9, 0, 0)))
                .isEmpty();
    }

    @Test
    void faelligIstDerTagNachDemLetztenManifestSonstDerTagDerAnlage() {
        assertThat(ManifestErzeugen.naechstes(Optional.of(TAG), () -> {
            throw new AssertionError("Register nicht nötig");
        })).isEqualTo(TAG.plusDays(1));
        // 23:30 UTC ist in Zürich schon der Folgetag
        assertThat(ManifestErzeugen.naechstes(Optional.empty(), () -> Optional.of(Instant.parse("2026-01-09T23:30:00Z"))))
                .isEqualTo(TAG);
        assertThatThrownBy(() -> ManifestErzeugen.naechstes(Optional.empty(), Optional::empty))
                .isInstanceOf(IllegalStateException.class);
    }
}
