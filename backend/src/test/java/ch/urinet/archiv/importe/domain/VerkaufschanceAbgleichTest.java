package ch.urinet.archiv.importe.domain;

import ch.urinet.archiv.importe.domain.VerkaufschanceAbgleich.Bestand;
import ch.urinet.archiv.importe.domain.VerkaufschanceAbgleich.Gelesen;
import ch.urinet.archiv.importe.domain.ZeilenPruefung.Ergebnis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Lesen und Abgleich einer Verkaufschance mit synthetischen Werten (Req 13.9). */
class VerkaufschanceAbgleichTest {

    private static final UUID KONTAKT = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID ANDERER = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    @Test
    void betragMitApostrophUndKomma() {
        assertThat(VerkaufschanceAbgleich.betrag("1'234.50")).contains(new BigDecimal("1234.50"));
        assertThat(VerkaufschanceAbgleich.betrag("1234,5")).contains(new BigDecimal("1234.50"));
        assertThat(VerkaufschanceAbgleich.betrag("-80")).contains(new BigDecimal("-80.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.234.50", "12.345", "abc", "12345678901", "CHF 10"})
    void ungueltigerBetrag(String text) {
        assertThat(VerkaufschanceAbgleich.betrag(text)).isEmpty();
    }

    @Test
    void datumSchweizerischOderIso() {
        assertThat(VerkaufschanceAbgleich.datum("8.11.26")).contains(LocalDate.of(2026, 11, 8));
        assertThat(VerkaufschanceAbgleich.datum("08.11.2026 14:30")).contains(LocalDate.of(2026, 11, 8));
        assertThat(VerkaufschanceAbgleich.datum("2026-11-08")).contains(LocalDate.of(2026, 11, 8));
        assertThat(VerkaufschanceAbgleich.datum("31.02.2026")).isEmpty();
        assertThat(VerkaufschanceAbgleich.datum("11/08/2026")).isEmpty();
    }

    @Test
    void ungueltigeWerteMachenDieZeileFehlerhaft() {
        assertThat(VerkaufschanceAbgleich.lesen(Map.of("betrag", "viel")).fehler())
                .isEqualTo(VerkaufschanceAbgleich.BETRAG_UNGUELTIG);
        assertThat(VerkaufschanceAbgleich.lesen(Map.of("datum", "morgen")).fehler())
                .isEqualTo(VerkaufschanceAbgleich.DATUM_UNGUELTIG);
    }

    @Test
    void ohneKontaktIdOderOhneKontaktFehlerhaft() {
        Gelesen ohne = VerkaufschanceAbgleich.lesen(Map.of("titel", "Offerte"));
        assertThat(VerkaufschanceAbgleich.pruefen(ohne, null, null).meldung())
                .isEqualTo(VerkaufschanceAbgleich.KONTAKT_FEHLT);
        Gelesen mit = VerkaufschanceAbgleich.lesen(Map.of("titel", "Offerte", "kontakt_crm_id", "K1"));
        KontaktAbgleich.Befund befund = VerkaufschanceAbgleich.pruefen(mit, null, null);
        assertThat(befund.ergebnis()).isEqualTo(Ergebnis.FEHLER);
        assertThat(befund.meldung()).isEqualTo(VerkaufschanceAbgleich.KONTAKT_UNBEKANNT);
    }

    @Test
    void neuGeaendertUnveraendert() {
        Gelesen zeile = VerkaufschanceAbgleich.lesen(Map.of("crm_id", "V1", "kontakt_crm_id", "K1",
                "titel", "Offerte", "betrag", "1'000"));
        assertThat(VerkaufschanceAbgleich.pruefen(zeile, KONTAKT, null).ergebnis()).isEqualTo(Ergebnis.NEU);
        Bestand gleich = new Bestand(UUID.randomUUID(), KONTAKT, "Offerte", "Gewonnen",
                new BigDecimal("1000.00"), LocalDate.of(2026, 1, 1));
        // leere Zellen (Phase, Datum) gelten nicht als Änderung
        assertThat(VerkaufschanceAbgleich.pruefen(zeile, KONTAKT, gleich).ergebnis())
                .isEqualTo(Ergebnis.UNVERAENDERT);
        assertThat(VerkaufschanceAbgleich.pruefen(zeile, ANDERER, gleich).ergebnis()).isEqualTo(Ergebnis.GEAENDERT);
        Bestand andererBetrag = new Bestand(gleich.id(), KONTAKT, "Offerte", null, new BigDecimal("900"), null);
        assertThat(VerkaufschanceAbgleich.pruefen(zeile, KONTAKT, andererBetrag).ergebnis())
                .isEqualTo(Ergebnis.GEAENDERT);
    }

    @Test
    void ohneCrmIdImmerNeuUndOhneFingerabdruck() {
        Gelesen zeile = VerkaufschanceAbgleich.lesen(Map.of("kontakt_crm_id", "K1", "titel", "Offerte"));
        Bestand bestand = new Bestand(UUID.randomUUID(), KONTAKT, "Offerte", null, null, null);
        assertThat(VerkaufschanceAbgleich.pruefen(zeile, KONTAKT, bestand).ergebnis()).isEqualTo(Ergebnis.NEU);
        assertThat(VerkaufschanceAbgleich.fingerabdruecke(zeile.werte())).isEmpty();
    }
}
