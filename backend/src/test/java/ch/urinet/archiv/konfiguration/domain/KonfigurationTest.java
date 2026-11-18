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
        for (String code : List.of("seit", "max", "referenz", "groesse", "status", "url", "dateiname")) {
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
        assertThat(konfiguration.ungueltigeDokumentKlassifikationen("FEHLT", Map.of(), Map.of())).isEmpty();
        assertThat(konfiguration.ungueltigeDokumentKlassifikationen("FEHLT",
                Map.of("fachgebiet", List.of("alt")), Map.of())).containsExactly("fachgebiet");
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
    void protokolliertUmbenennungUndStandardwertMitAltemUndNeuemWert() {
        var alt = klassifikation("fachgebiet", "Altname", "Bau");
        var neu = klassifikation("fachgebiet", "Neuname", "Hochbau");
        var artVorher = artMitKlassifikationen("MERKBLATT",
                List.of(new ArtKlassifikation("fachgebiet", false, "bau")));
        var artNachher = artMitKlassifikationen("MERKBLATT",
                List.of(new ArtKlassifikation("fachgebiet", false, "elektro")));
        var vorher = new Konfiguration(1, einstellungen(), List.of(artVorher), List.of(alt));
        var nachher = new Konfiguration(2, einstellungen(), List.of(artNachher), List.of(neu));

        assertThat(vorher.gleicherInhalt(nachher)).isFalse();
        assertThat(vorher.aenderungen(nachher)).containsExactlyInAnyOrder(
                new Konfiguration.Aenderung("arten.MERKBLATT.klassifikationen.fachgebiet.standardWert",
                        "bau", "elektro"),
                new Konfiguration.Aenderung("klassifikationen.fachgebiet.name", "Altname", "Neuname"),
                new Konfiguration.Aenderung("klassifikationen.fachgebiet.werte.wert.name", "Bau", "Hochbau"));
    }

    private static Konfiguration.Art art(String code, String vertraulichkeit, boolean aktiv, boolean selbstAusgestellt) {
        return new Konfiguration.Art(code, code, null, List.of("frei"), List.of("titel"), "{\"type\":\"object\"}",
                vertraulichkeit, new Konfiguration.Aufbewahrung(null, null), false, aktiv, selbstAusgestellt,
                List.of(), null, null, true, List.of(), null, 0);
    }

    private static Konfiguration.Art artMitKlassifikationen(String code, List<ArtKlassifikation> zuordnungen) {
        return new Konfiguration.Art(code, code, null, List.of("frei"), List.of("titel"),
                Konfiguration.OHNE_ZUSATZ, "NORMAL", new Konfiguration.Aufbewahrung(null, null), false, true,
                false, List.of(), null, null, true, List.of(), null, 0, zuordnungen);
    }

    @Test
    void fristfeldMussPflichtfeldDerArtSein() {
        var art = new Konfiguration.Art("PROBE", "Probe", null, List.of("frei"), List.of("titel"),
                "{\"type\":\"object\"}", "NORMAL", new Konfiguration.Aufbewahrung(10, "FELD", "sitzung"),
                false, true);
        assertThat(new Konfiguration(1, einstellungen(), List.of(art)).pruefen())
                .extracting(Konfiguration.Befund::code).containsExactly("FRISTFELD_UNGUELTIG");
    }

    @Test
    void erlaubtBeiMehrfachenKlassifikationenJedenBehaltenenInaktivenWert() {
        var klassifikation = new Klassifikation("fachgebiet", "Fachgebiet", null, List.of(
                new KlassifikationWert("bau", "Bau", null, 0, false),
                new KlassifikationWert("elektro", "Elektro", null, 1, true)), true, false, true, false, true);
        var art = artMitKlassifikationen("MERKBLATT", List.of(new ArtKlassifikation("fachgebiet", false, null)));
        var konfiguration = new Konfiguration(1, einstellungen(), List.of(art), List.of(klassifikation));
        Map<String, List<String>> alt = Map.of("fachgebiet", List.of("bau"));

        assertThat(konfiguration.ungueltigeDokumentKlassifikationen("MERKBLATT",
                Map.of("fachgebiet", List.of("bau", "elektro")), alt)).isEmpty();
        assertThat(konfiguration.ungueltigeDokumentKlassifikationen("MERKBLATT",
                Map.of("fachgebiet", List.of("bau", "elektro")), Map.of())).containsExactly("fachgebiet");
    }

    @Test
    void ohneArtIstJederGesetzteCodeUngueltig() {
        var konfiguration = new Konfiguration(1, einstellungen(), List.of(), List.of(
                klassifikation("fachgebiet", "Fachgebiet", "Bau")));

        assertThat(konfiguration.ungueltigeDokumentKlassifikationen(null,
                Map.of("fachgebiet", List.of("wert"), "leer", List.of()), Map.of()))
                .containsExactly("fachgebiet", "leer");
        assertThat(konfiguration.ungueltigeDokumentKlassifikationen(null, Map.of(), Map.of())).isEmpty();
    }

    @Test
    void veralteteWerteMeldenKeinePflichtLuecke() {
        var klassifikation = klassifikation("fachgebiet", "Fachgebiet", "Bau");
        var art = artMitKlassifikationen("MERKBLATT", List.of(new ArtKlassifikation("fachgebiet", true, null)));
        var konfiguration = new Konfiguration(1, einstellungen(), List.of(art), List.of(klassifikation));

        assertThat(konfiguration.veralteteDokumentKlassifikationen("MERKBLATT", Map.of(), Map.of())).isEmpty();
        assertThat(konfiguration.ungueltigeDokumentKlassifikationen("MERKBLATT", Map.of(), Map.of()))
                .containsExactly("fachgebiet");
    }

    @Test
    void inaktiveKlassifikationDarfWederPflichtNochStandardwertTragen() {
        var inaktiv = new Klassifikation("fachgebiet", "Fachgebiet", null,
                List.of(new KlassifikationWert("wert", "Wert", null, 0, true)), false, false, true, false, false);
        for (var zuordnung : List.of(new ArtKlassifikation("fachgebiet", true, null),
                new ArtKlassifikation("fachgebiet", false, "wert"))) {
            var konfiguration = new Konfiguration(1, einstellungen(),
                    List.of(artMitKlassifikationen("MERKBLATT", List.of(zuordnung))), List.of(inaktiv));
            assertThat(konfiguration.pruefen()).extracting(Konfiguration.Befund::code)
                    .contains("KLASSIFIKATION_UNGUELTIG");
        }
        var frei = new Konfiguration(1, einstellungen(), List.of(artMitKlassifikationen("MERKBLATT",
                List.of(new ArtKlassifikation("fachgebiet", false, null)))), List.of(inaktiv));
        assertThat(frei.pruefen()).isEmpty();
    }

    @Test
    void begrenztAnzahlUndZeichenVonKlassifikationenUndWerten() {
        var zuViele = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> klassifikation("k" + i, "K" + i, "W")).toList();
        assertThat(new Konfiguration(1, einstellungen(), List.of(), zuViele).pruefen())
                .extracting(Konfiguration.Befund::code).contains("ANZAHL_UNGUELTIG");

        var werte = java.util.stream.IntStream.range(0, 501)
                .mapToObj(i -> new KlassifikationWert("w" + i, "W", null, i, true)).toList();
        var zuvielWerte = new Klassifikation("fachgebiet", "Fachgebiet", null, werte, false, false, true, false, true);
        assertThat(new Konfiguration(1, einstellungen(), List.of(), List.of(zuvielWerte)).pruefen())
                .extracting(Konfiguration.Befund::code).contains("ANZAHL_UNGUELTIG");

        for (String code : List.of("a".repeat(41), "Gross", "1abc", "mit-strich")) {
            assertThat(new Konfiguration(1, einstellungen(), List.of(),
                    List.of(klassifikation(code, "Name", "Wert"))).pruefen())
                    .extracting(Konfiguration.Befund::code).contains("CODE_UNGUELTIG");
        }
        assertThat(new Konfiguration(1, einstellungen(), List.of(),
                List.of(klassifikation("a".repeat(40), "Name", "Wert"))).pruefen()).isEmpty();
    }

    @Test
    void lehntSteuerzeichenInNamenAbUndErlaubtZeilenumbruchInBeschreibungen() {
        for (String name : List.of("Fach\ngebiet", "Fach\u0007gebiet", "Fach\u0085gebiet")) {
            assertThat(new Konfiguration(1, einstellungen(), List.of(),
                    List.of(klassifikation("fachgebiet", name, "Wert"))).pruefen())
                    .extracting(Konfiguration.Befund::code).contains("TEXT_UNGUELTIG");
            assertThat(new Konfiguration(1, einstellungen(), List.of(),
                    List.of(klassifikation("fachgebiet", "Fachgebiet", name))).pruefen())
                    .extracting(Konfiguration.Befund::code).contains("TEXT_UNGUELTIG");
        }
        var mitBeschreibung = new Klassifikation("fachgebiet", "Fachgebiet", "Zeile 1\r\n\tZeile 2",
                List.of(new KlassifikationWert("wert", "Wert", "Zeile 1\nZeile 2", 0, true)),
                false, false, true, false, true);
        assertThat(new Konfiguration(1, einstellungen(), List.of(), List.of(mitBeschreibung)).pruefen()).isEmpty();
        var steuerzeichen = new Klassifikation("fachgebiet", "Fachgebiet", "Text\u001b[0m",
                List.of(new KlassifikationWert("wert", "Wert", null, 0, true)), false, false, true, false, true);
        assertThat(new Konfiguration(1, einstellungen(), List.of(), List.of(steuerzeichen)).pruefen())
                .extracting(Konfiguration.Befund::code).contains("TEXT_UNGUELTIG");
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
