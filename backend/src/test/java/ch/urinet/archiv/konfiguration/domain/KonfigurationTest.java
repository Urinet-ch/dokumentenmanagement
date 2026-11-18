package ch.urinet.archiv.konfiguration.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KonfigurationTest {

    @Test
    void pruefenLiefertAlleDerzeitMoeglichenBefunde() {
        var art = new Konfiguration.Art("RECHNUNG", "Rechnung", null, List.of("finanz"), List.of("titel"),
                "{\"type\":\"object\"}", "NORMAL", new Konfiguration.Aufbewahrung(null, null), false, true);

        var konfiguration = new Konfiguration(0,
                new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of())),
                List.of(art, art));

        assertThat(konfiguration.pruefen()).containsExactly(
                new Konfiguration.Befund("version", "VERSION_UNGUELTIG",
                        "Die Konfigurationsversion muss positiv sein."),
                new Konfiguration.Befund("arten.RECHNUNG", "DOKUMENTART_DOPPELT",
                        "Der Dokumentart-Code kommt mehrfach vor."));
    }

    @Test
    void aenderungToStringGibtKennungenNichtAus() {
        var kennung = new Konfiguration.Aenderung("einstellungen.kennungen.uid", "PROBE-ALT", "PROBE-NEU");
        var andere = new Konfiguration.Aenderung("einstellungen.geschaeftsjahrEnde", "06-30", "12-31");

        assertThat(kennung.toString()).doesNotContain("PROBE-ALT").doesNotContain("PROBE-NEU");
        assertThat(andere.toString()).contains("06-30").contains("12-31");
    }

    /** Die Pfade, die {@link Konfiguration#aenderungen} für Kennungen liefert, gelten als ohne Werte (Req 1.3). */
    @Test
    void geaenderteKennungenSindGenauDieAenderungenOhneWerte() {
        var vorher = new Konfiguration(1, new Konfiguration.Einstellungen("12-31",
                new Konfiguration.Kennungen("PROBE-UID-ALT", List.of("PROBE-IBAN-ALT"))), List.of());
        var nachher = new Konfiguration(2, new Konfiguration.Einstellungen("06-30",
                new Konfiguration.Kennungen("PROBE-UID-NEU", List.of("PROBE-IBAN-NEU"))), List.of());

        var aenderungen = vorher.aenderungen(nachher);

        assertThat(aenderungen).extracting(Konfiguration.Aenderung::pfad).containsExactly(
                "einstellungen.geschaeftsjahrEnde", "einstellungen.kennungen.uid", "einstellungen.kennungen.ibans");
        assertThat(aenderungen).extracting(Konfiguration.Aenderung::ohneWerte).containsExactly(false, true, true);
        assertThat(aenderungen.toString() + nachher).doesNotContain("PROBE-UID", "PROBE-IBAN");
    }

    @Test
    void kopiertListenUnveraenderlich() {
        var ibans = new java.util.ArrayList<>(List.of("TEST-IBAN-1"));
        var kennungen = new Konfiguration.Kennungen(null, ibans);
        ibans.add("TEST-IBAN-2");

        assertThat(kennungen.ibans()).containsExactly("TEST-IBAN-1");
        assertThatThrownBy(() -> kennungen.ibans().add("TEST-IBAN-2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void prueftArtReferenzenAufAktivitaetUndVertraulichkeit() {
        var ziel = art("ZIEL", "NORMAL", true, false);
        var quelle = new Konfiguration.Art("QUELLE", "Quelle", null, List.of("frei"), List.of("titel"),
                "{\"type\":\"object\"}", "INTERN", new Konfiguration.Aufbewahrung(null, null), false, true,
                false, List.of(), "ZIEL", null, true, List.of(), null, 0);

        assertThat(new Konfiguration(1, einstellungen(), List.of(quelle, ziel)).pruefen())
                .extracting(Konfiguration.Befund::code).containsExactly("ART_REFERENZ_UNGUELTIG");
    }

    @Test
    void prueftFesteEigenschaftenDerWissensquelle() {
        var art = new Konfiguration.Art("WISSENSQUELLE", "Wissensquelle", null, List.of("frei"),
                List.of("titel", "datum"), "{\"type\":\"object\"}", "NORMAL",
                new Konfiguration.Aufbewahrung(null, null), false, true, false, List.of(), null, null, false,
                List.of(), null, 0);
        assertThat(new Konfiguration(1, einstellungen(), List.of(art)).pruefen()).isEmpty();

        var ungueltig = new Konfiguration.Art("WISSENSQUELLE", "Wissensquelle", null, List.of("frei"),
                List.of("titel", "datum"), "{\"type\":\"object\"}", "NORMAL",
                new Konfiguration.Aufbewahrung(null, null), false, true, false, List.of(), null, null, true,
                List.of(), null, 0);
        assertThat(new Konfiguration(1, einstellungen(), List.of(ungueltig)).pruefen())
                .extracting(Konfiguration.Befund::code).containsExactly("WISSENSQUELLE_UNGUELTIG");
    }

    private static Konfiguration.Art art(String code, String vertraulichkeit, boolean aktiv, boolean selbstAusgestellt) {
        return new Konfiguration.Art(code, code, null, List.of("frei"), List.of("titel"), "{\"type\":\"object\"}",
                vertraulichkeit, new Konfiguration.Aufbewahrung(null, null), false, aktiv, selbstAusgestellt,
                List.of(), null, null, true, List.of(), null, 0);
    }

    @Test
    void fristfeldMussPflichtfeldDerArtSein() {
        var art = new Konfiguration.Art("PROBE", "Probe", null, List.of("frei"), List.of("titel"),
                "{\"type\":\"object\"}", "NORMAL", new Konfiguration.Aufbewahrung(10, "FELD", "sitzung"),
                false, true);
        assertThat(new Konfiguration(1, einstellungen(), List.of(art)).pruefen())
                .extracting(Konfiguration.Befund::code).containsExactly("FRISTFELD_UNGUELTIG");
    }

    private static Konfiguration.Einstellungen einstellungen() {
        return new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of()));
    }
}
