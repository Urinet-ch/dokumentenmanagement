package ch.urinet.archiv.konfiguration.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    void prueftKlassifikationsCodesReservierteParameterUndArtzuordnungen() {
        var klassifikation = new Klassifikation("seit", "Fachgebiet", null, List.of(
                new KlassifikationWert("bad", "Bau", null, 0, true)), true, true, true, true, true);
        var art = new Konfiguration.Art("MERKBLATT", "Merkblatt", null, List.of("frei"), List.of("titel"),
                "{\"type\":\"object\"}", "NORMAL", new Konfiguration.Aufbewahrung(null, null), false, true,
                false, List.of(), null, null, false, List.of(), null, 0,
                List.of(new ArtKlassifikation("unbekannt", true, null)));

        assertThat(new Konfiguration(1, einstellungen(), List.of(art), List.of(klassifikation)).pruefen())
                .extracting(Konfiguration.Befund::code)
                .containsExactly("PARAMETER_RESERVIERT", "KLASSIFIKATION_UNGUELTIG");
    }

    @Test
    void prueftAlleZusaetzlichenReserviertenPublicParameter() {
        for (String code : List.of("seit", "max", "referenz", "groesse", "status", "url")) {
            var klassifikation = new Klassifikation(code, "Fachgebiet", null, List.of(),
                    false, false, true, true, true);
            assertThat(new Konfiguration(1, einstellungen(), List.of(), List.of(klassifikation)).pruefen())
                    .as("Klassifikations-Code %s", code)
                    .extracting(Konfiguration.Befund::code).contains("PARAMETER_RESERVIERT");
        }
    }

    @Test
    void prueftDokumentKlassifikationenUndMeldetBetroffeneCodes() {
        var klassifikation = new Klassifikation("fachgebiet", "Fachgebiet", null, List.of(
                new KlassifikationWert("alt", "Alt", null, 0, false),
                new KlassifikationWert("neu", "Neu", null, 1, true)), false, false, true, false, true);
        var art = artMitKlassifikationen("MERKBLATT",
                List.of(new ArtKlassifikation("fachgebiet", true, null)));
        var konfiguration = new Konfiguration(1, einstellungen(), List.of(art), List.of(klassifikation));

        assertThat(konfiguration.ungueltigeDokumentKlassifikationen("MERKBLATT",
                Map.of("fachgebiet", List.of("alt", "neu"), "unbekannt", List.of("wert")), Map.of()))
                .containsExactly("fachgebiet", "unbekannt");
        assertThat(konfiguration.ungueltigeDokumentKlassifikationen("MERKBLATT", Map.of(), Map.of()))
                .containsExactly("fachgebiet");
        assertThat(konfiguration.ungueltigeDokumentKlassifikationen("FEHLT", Map.of(), Map.of()))
                .containsExactly("art");
    }

    @Test
    void erlaubtUnveraenderteInaktiveWerteUndVorbelegungNurBeimArtwechsel() {
        var klassifikation = new Klassifikation("fachgebiet", "Fachgebiet", null, List.of(
                new KlassifikationWert("alt", "Alt", null, 0, false),
                new KlassifikationWert("bau", "Bau", null, 1, true)), false, false, true, false, true);
        var art = artMitKlassifikationen("MERKBLATT", List.of(
                new ArtKlassifikation("fachgebiet", false, "bau"),
                new ArtKlassifikation("zielgruppe", false, "alle")));
        var konfiguration = new Konfiguration(1, einstellungen(), List.of(art), List.of(klassifikation));
        Map<String, List<String>> alt = Map.of("fachgebiet", List.of("alt"));

        assertThat(konfiguration.ungueltigeDokumentKlassifikationen("MERKBLATT", alt, alt)).isEmpty();
        assertThat(konfiguration.ungueltigeDokumentKlassifikationen("MERKBLATT", alt, Map.of()))
                .containsExactly("fachgebiet");
        assertThat(konfiguration.beimArtwechselVorbelegen("MERKBLATT", Map.of(
                "fachgebiet", List.of("alt"), "zielgruppe", List.of(), "entfernt", List.of("wert"))))
                .containsExactlyInAnyOrderEntriesOf(Map.of("fachgebiet", List.of("alt"),
                        "zielgruppe", List.of("alle")));
    }

    @Test
    void protokolliertKlassifikationsAenderungenOhneKlartextWerte() {
        var alt = klassifikation("fachgebiet", "Altname", "geheim");
        var neu = klassifikation("fachgebiet", "Neuname", "andererWert");
        var artVorher = artMitKlassifikationen("MERKBLATT",
                List.of(new ArtKlassifikation("fachgebiet", false, "wert")));
        var artNachher = artMitKlassifikationen("MERKBLATT",
                List.of(new ArtKlassifikation("fachgebiet", false, "anderer_wert")));
        var vorher = new Konfiguration(1, einstellungen(), List.of(artVorher), List.of(alt));
        var nachher = new Konfiguration(2, einstellungen(), List.of(artNachher), List.of(neu));

        assertThat(vorher.aenderungen(nachher))
                .extracting(Konfiguration.Aenderung::alt)
                .doesNotContain("Altname", "Neuname", "geheim", "andererWert", "wert", "anderer_wert");
        assertThat(vorher.aenderungen(nachher))
                .extracting(Konfiguration.Aenderung::neu)
                .doesNotContain("Altname", "Neuname", "geheim", "andererWert", "wert", "anderer_wert");
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

    private static Konfiguration.Art artMitKlassifikationen(String code, List<ArtKlassifikation> zuordnungen) {
        return new Konfiguration.Art(code, code, null, List.of("frei"), List.of("titel"),
                Konfiguration.OHNE_ZUSATZ, "NORMAL", new Konfiguration.Aufbewahrung(null, null), false, true,
                false, List.of(), null, null, true, List.of(), null, 0, zuordnungen);
    }

    private static Konfiguration.Einstellungen einstellungen() {
        return new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of()));
    }

    private static Klassifikation klassifikation(String code, String name, String wertName) {
        return new Klassifikation(code, name, null,
                List.of(new KlassifikationWert("wert", wertName, null, 0, true)),
                false, false, true, false, true);
    }
}
