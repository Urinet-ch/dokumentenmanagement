package ch.urinet.archiv.dokumentart.domain;

import ch.urinet.archiv.dokumentart.domain.Aufbewahrung.Neuberechnung;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tabellentests zu {@code aufbewahren_bis} (Req 9.3, 9.7, 9.8; design.md «Dokumentart und Aufbewahrung»). */
class AufbewahrungTest {

    private static final LocalDate HEUTE = LocalDate.of(2026, 11, 10);
    private static final Aufbewahrungsregel ZEHN_GJ = new Aufbewahrungsregel(10, AufbewahrungAb.GESCHAEFTSJAHR);
    private static final Aufbewahrungsregel ZEHN_VERTRAG = new Aufbewahrungsregel(10, AufbewahrungAb.GUELTIG_BIS);

    @ParameterizedTest(name = "Beleg {0}, GJ-Ende {1} → GJ {2}")
    @CsvSource({
            "2026-03-15, 12-31, 2026",
            "2026-12-31, 12-31, 2026",
            "2026-06-30, 06-30, 2026",
            "2026-08-15, 06-30, 2027",
            "2026-01-01, 06-30, 2026",
            "2028-02-29, 02-29, 2028",
            "2027-02-28, 02-29, 2027",
            "2027-03-01, 02-29, 2028",
            "2024-02-29, 12-31, 2024"})
    void geschaeftsjahrAusBelegdatum(LocalDate beleg, String gjEnde, int erwartet) {
        assertThat(Aufbewahrung.geschaeftsjahrVorschlagen(beleg, null, ende(gjEnde))).isEqualTo(erwartet);
    }

    @ParameterizedTest(name = "Eingang {0} ohne Belegdatum → GJ {1}")
    @CsvSource({
            // 31.12.2026 23:30 UTC ist in Zürich schon der 01.01.2027
            "2026-12-31T23:30:00Z, 2027",
            "2026-12-31T22:59:59Z, 2026",
            "2026-06-30T21:59:59Z, 2026"})
    void geschaeftsjahrOhneBelegdatumAusEingangInZuerich(Instant eingang, int erwartet) {
        assertThat(Aufbewahrung.geschaeftsjahrVorschlagen(null, eingang, ende("12-31"))).isEqualTo(erwartet);
    }

    @Test
    void geschaeftsjahrOhneBelegdatumUndEingangIstUngueltig() {
        assertThatThrownBy(() -> Aufbewahrung.geschaeftsjahrVorschlagen(null, null, ende("12-31")))
                .isInstanceOfSatisfying(FachFehler.class, f -> assertThat(f.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
    }

    @ParameterizedTest(name = "Beleg {0}, GJ {1}, GJ-Ende {2} → {3}")
    @CsvSource({
            "2026-03-15,     , 12-31, 2036-12-31",
            "2026-08-15,     , 06-30, 2037-06-30",
            "2026-03-15, 2025, 12-31, 2035-12-31",
            "2028-02-29,     , 02-29, 2038-02-28",
            "2024-02-29,     , 12-31, 2034-12-31"})
    void abGeschaeftsjahr(LocalDate beleg, Integer gj, String gjEnde, LocalDate erwartet) {
        Aufbewahrung ergebnis = Aufbewahrung.berechne(ZEHN_GJ, beleg, null, gj, null, ende(gjEnde), HEUTE);
        assertThat(ergebnis).isEqualTo(new Aufbewahrung(erwartet, false));
    }

    @Test
    void ohneBelegdatumZaehltDerEingang() {
        Instant eingang = Instant.parse("2026-12-31T23:30:00Z");
        assertThat(Aufbewahrung.berechne(ZEHN_GJ, null, eingang, null, null, ende("12-31"), HEUTE).bis())
                .isEqualTo(LocalDate.of(2037, 12, 31));
    }

    @ParameterizedTest(name = "gueltig_bis {0} → {1}")
    @CsvSource({
            "2027-06-30, 2037-06-30",
            "2028-02-29, 2038-02-28"})
    void abGueltigBis(LocalDate gueltigBis, LocalDate erwartet) {
        assertThat(Aufbewahrung.berechne(ZEHN_VERTRAG, LocalDate.of(2026, 1, 5), null, null, gueltigBis,
                ende("12-31"), HEUTE)).isEqualTo(new Aufbewahrung(erwartet, false));
    }

    @Test
    void vertragOhneEndeIstUnbefristet() {
        assertThat(Aufbewahrung.berechne(ZEHN_VERTRAG, LocalDate.of(2026, 1, 5), null, null, null, ende("12-31"), HEUTE))
                .isEqualTo(new Aufbewahrung(null, false));
    }

    @Test
    void ohneJahreIstUnbefristet() {
        assertThat(Aufbewahrung.berechne(Aufbewahrungsregel.UNBEFRISTET, LocalDate.of(1990, 1, 5), null, null, null,
                ende("12-31"), HEUTE)).isEqualTo(new Aufbewahrung(null, false));
    }

    @ParameterizedTest(name = "Beleg {0} → abgelaufen {1}")
    @CsvSource({
            "2014-05-01, true",
            "2015-12-31, false",
            "2016-01-01, false"})
    void fristAbgelaufenBeimAltbestand(LocalDate beleg, boolean abgelaufen) {
        // Beleg 2015 → 31.12.2025 liegt vor HEUTE; Frist am heutigen Tag gilt noch nicht als abgelaufen
        LocalDate heute = beleg.getYear() == 2015 ? LocalDate.of(2025, 12, 31) : HEUTE;
        assertThat(Aufbewahrung.berechne(ZEHN_GJ, beleg, null, null, null, ende("12-31"), heute).fristAbgelaufen())
                .isEqualTo(abgelaufen);
    }

    @Test
    void wechselDerArtRechnetMitDerNeuenRegel() {
        LocalDate beleg = LocalDate.of(2026, 3, 15);
        LocalDate alt = Aufbewahrung.berechne(ZEHN_GJ, beleg, null, null, null, ende("12-31"), HEUTE).bis();
        LocalDate neu = Aufbewahrung.berechne(new Aufbewahrungsregel(5, AufbewahrungAb.GESCHAEFTSJAHR), beleg, null,
                null, null, ende("12-31"), HEUTE).bis();
        assertThat(neu).isEqualTo(LocalDate.of(2031, 12, 31));
        assertThat(Aufbewahrung.entscheiden(alt, neu)).isEqualTo(Neuberechnung.KONFLIKT);
        LocalDate unbefristet = Aufbewahrung.berechne(Aufbewahrungsregel.UNBEFRISTET, beleg, null, null, null,
                ende("12-31"), HEUTE).bis();
        assertThat(Aufbewahrung.entscheiden(alt, unbefristet)).isEqualTo(Neuberechnung.VERLAENGERN);
    }

    @ParameterizedTest(name = "bisher {0}, neu {1} → {2}")
    @CsvSource({
            "2036-12-31, 2036-12-31, GLEICH",
            "2036-12-31, 2037-12-31, VERLAENGERN",
            "2036-12-31,           , VERLAENGERN",
            "2036-12-31, 2035-12-31, KONFLIKT",
            "          , 2036-12-31, KONFLIKT",
            "          ,           , GLEICH"})
    void neuberechnungVerlaengertNurUndBehaeltSonstDieFrist(LocalDate bisher, LocalDate neu, Neuberechnung erwartet) {
        assertThat(Aufbewahrung.entscheiden(bisher, neu)).isEqualTo(erwartet);
    }

    @ParameterizedTest
    @CsvSource({"1899", "3000"})
    void unplausiblesGeschaeftsjahrIstUngueltig(int gj) {
        assertThatThrownBy(() -> Aufbewahrung.berechne(ZEHN_GJ, null, null, gj, null, ende("12-31"), HEUTE))
                .isInstanceOf(FachFehler.class);
    }

    private static MonthDay ende(String mmTt) {
        return MonthDay.parse("--" + mmTt);
    }
}
