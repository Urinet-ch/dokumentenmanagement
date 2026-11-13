package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regel der Manifest-Überwachung (Req 15.8, 16.5): erster fälliger Tag ohne Manifest, Frist streng, ein Auftrag in FEHLER
 * nach dem letzten Fortschritt. Dass ein neuerer Auftrag in OFFEN, LAEUFT oder FERTIG den letzten Fehlschlag nicht
 * verdeckt, zeigt {@code JdbcAuftragStandTest}.
 */
class ManifestBefundeTest {

    private static final Duration FRIST = Duration.ofHours(6);
    private static final LocalDate TAG = LocalDate.of(2026, 1, 10);

    private static Instant zuerich(int jahr, int monat, int tag, int stunde, int minute, int sekunde) {
        return LocalDateTime.of(jahr, monat, tag, stunde, minute, sekunde).atZone(ManifestFenster.ZUERICH).toInstant();
    }

    private static final Befund FEHLT_AM_TAG = new Befund(Befund.Quelle.MANIFEST, "2026-01-10", "FEHLT");

    private static Optional<Befund> befund(LocalDate faellig, Optional<Instant> fortschritt, Optional<Instant> fehler,
                                           Instant jetzt) {
        return ManifestBefunde.befund(faellig, fortschritt, Optional.empty(), fehler, FRIST, jetzt);
    }

    @Test
    void derBefundNenntDatumUndStatus() {
        assertThat(befund(TAG, Optional.empty(), Optional.empty(), zuerich(2026, 11, 13, 9, 0, 0)))
                .contains(FEHLT_AM_TAG);
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

    @Test
    void einFehlschlagNachDemLetztenFortschrittMeldetSofort() {
        Instant jetzt = zuerich(2026, 1, 11, 4, 30, 0);
        Instant fortschritt = zuerich(2026, 1, 10, 3, 31, 0);
        assertThat(befund(TAG, Optional.of(fortschritt), Optional.of(zuerich(2026, 1, 11, 4, 21, 0)), jetzt))
                .contains(FEHLT_AM_TAG);
        assertThat(befund(TAG, Optional.of(fortschritt), Optional.empty(), jetzt)).isEmpty();
    }

    /**
     * Review PR #114: Ein Neustart reiht nach dem Fehlschlag einen neuen Auftrag ein (OFFEN, LAEUFT oder ohne Arbeit
     * FERTIG). Die Regel sieht nur den letzten Fehlschlag, der Befund bleibt innerhalb der Frist bis zum nächsten
     * Fortschritt bestehen, sonst käme nach dem nächsten Fehlschlag eine zweite Mail für denselben Tag.
     */
    @Test
    void derBefundBleibtBisZumNaechstenFortschritt() {
        Instant fehler = zuerich(2026, 1, 11, 4, 21, 0);
        Instant fortschritt = zuerich(2026, 1, 10, 3, 31, 0);
        for (Instant jetzt : new Instant[]{zuerich(2026, 1, 11, 5, 15, 0), zuerich(2026, 1, 11, 5, 59, 0)}) {
            assertThat(befund(TAG, Optional.of(fortschritt), Optional.of(fehler), jetzt)).contains(FEHLT_AM_TAG);
        }
        // Fortschritt nach dem Fehlschlag: Der Tag ist nachgeholt (anderes fälliges Datum) oder der Lauf kommt voran
        Instant danach = zuerich(2026, 1, 11, 5, 40, 0);
        assertThat(befund(TAG, Optional.of(danach), Optional.of(fehler), zuerich(2026, 1, 11, 6, 0, 0))).isEmpty();
        assertThat(befund(TAG, Optional.of(fehler), Optional.of(fehler), zuerich(2026, 1, 11, 6, 0, 0))).isEmpty();
    }

    @Test
    void ohneManifestZaehltDerFehlschlagNachDerAnlage() {
        Instant anlage = zuerich(2026, 1, 10, 8, 0, 0);
        Instant jetzt = zuerich(2026, 1, 11, 4, 0, 0);
        assertThat(ManifestBefunde.befund(TAG, Optional.empty(), Optional.of(anlage),
                Optional.of(zuerich(2026, 1, 10, 7, 0, 0)), FRIST, jetzt)).isEmpty();
        assertThat(ManifestBefunde.befund(TAG, Optional.empty(), Optional.of(anlage),
                Optional.of(zuerich(2026, 1, 11, 0, 30, 0)), FRIST, jetzt)).contains(FEHLT_AM_TAG);
        // nach Ablauf der Frist meldet (a) auch ohne Fehlschlag
        assertThat(ManifestBefunde.befund(TAG, Optional.empty(), Optional.of(anlage), Optional.empty(), FRIST,
                zuerich(2026, 1, 11, 6, 0, 1))).contains(FEHLT_AM_TAG);
    }

    @Test
    void einNochNichtFaelligerTagMeldetAuchBeiFehlerNichts() {
        // gestern ist erst 15 Minuten nach Mitternacht fällig (ManifestErzeugen.NACHLAUF)
        Optional<Instant> fehler = Optional.of(zuerich(2026, 1, 11, 0, 5, 0));
        assertThat(befund(TAG, Optional.empty(), fehler, zuerich(2026, 1, 11, 0, 10, 0))).isEmpty();
        assertThat(befund(TAG, Optional.empty(), fehler, zuerich(2026, 1, 11, 0, 20, 0))).isPresent();
        assertThat(befund(TAG.plusDays(1), Optional.empty(), fehler, zuerich(2026, 1, 11, 9, 0, 0))).isEmpty();
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
