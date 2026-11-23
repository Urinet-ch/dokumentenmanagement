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
    void geschaeftsjahrEndeMussEinTagDesMonatsSein() {
        for (String ungueltig : List.of("02-30", "04-31", "13-01", "00-10", "12-00", "1231", "12-31x")) {
            assertThat(new Konfiguration(1, new Konfiguration.Einstellungen(ungueltig, null), List.of()).pruefen())
                    .as(ungueltig).extracting(Konfiguration.Befund::pfad)
                    .containsExactly("einstellungen.geschaeftsjahrEnde");
        }
        for (String gueltig : List.of("02-29", "06-30", "12-31")) {
            assertThat(new Konfiguration(1, new Konfiguration.Einstellungen(gueltig, null), List.of()).pruefen())
                    .as(gueltig).isEmpty();
        }
    }

    @Test
    void kiTexteStehenMitWertenImProtokoll() {
        assertThat(new Konfiguration.Aenderung("kiTexte.rolle", "alt", "neu").ohneWerte()).isFalse();
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
    void lehntDieEntfalleneDokumentartWissensquelleAb() {
        var art = new Konfiguration.Art("WISSENSQUELLE", "Wissensquelle", null, List.of("frei"),
                List.of("titel", "datum"), "{\"type\":\"object\"}", "NORMAL",
                new Konfiguration.Aufbewahrung(null, null), false, true, false, List.of(), null, null, false,
                List.of(), null, 0);
        assertThat(new Konfiguration(1, einstellungen(), List.of(art)).pruefen())
                .extracting(Konfiguration.Befund::code).containsExactly("DOKUMENTART_ENTFALLEN");

        var ungueltig = new Konfiguration.Art("WISSENSQUELLE", "Wissensquelle", null, List.of("frei"),
                List.of("titel", "datum"), "{\"type\":\"object\"}", "NORMAL",
                new Konfiguration.Aufbewahrung(null, null), false, true, false, List.of(), null, null, true,
                List.of(), null, 0);
        assertThat(new Konfiguration(1, einstellungen(), List.of(ungueltig)).pruefen())
                .extracting(Konfiguration.Befund::code).containsExactly("DOKUMENTART_ENTFALLEN");
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

    @Test
    void prueftRegelparameterArtUndFeldbezug() {
        var gueltig = new Regel("pflicht", "MERKBLATT", "PFLICHT_WENN",
                Map.of("wenn", Map.of("feld", "titel", "gleich", "Test"), "dann", List.of("datum")),
                "FEHLER", "Datum ist erforderlich.", true);
        assertThat(new Konfiguration(1, einstellungen(), List.of(art("MERKBLATT", "NORMAL", true, false)),
                List.of(), List.of(gueltig)).pruefen()).isEmpty();

        var ungueltig = new Regel("pflicht", "FEHLT", "PFLICHT_WENN",
                Map.of("wenn", Map.of("feld", "unbekannt", "gleich", "Test"), "dann", List.of("datum")),
                "KRITISCH", "", true);
        assertThat(new Konfiguration(1, einstellungen(), List.of(art("MERKBLATT", "NORMAL", true, false)),
                List.of(), List.of(ungueltig)).pruefen())
                .extracting(Konfiguration.Befund::code)
                .containsExactly("TEXT_UNGUELTIG", "SCHWERE_UNGUELTIG", "REGEL_ART_UNGUELTIG");
    }

    @Test
    void validiertRegelfelderGegenZusatzschemaUndMustergruppe() {
        var art = new Konfiguration.Art("RECHNUNG", "Rechnung", null, List.of("finanz", "frei"),
                List.of("titel"), "{\"type\":\"object\"}", "NORMAL",
                new Konfiguration.Aufbewahrung(null, null), false, true, true,
                List.of("^R(?<nr>\\\\d+)$"), null, null, true, List.of(), null, 0, List.of(),
                Map.of("jahr", "integer", "periode", "date"));
        var folge = new Regel("folge", "RECHNUNG", "FOLGE",
                Map.of("feld", "zusatz.jahr", "zeitraum", Map.of("feld", "zusatz.jahr")),
                "WARNUNG", "Folge prüfen.", true);
        var erwartet = new Regel("erwartet", "RECHNUNG", "ERWARTET",
                Map.of("je", "MONAT", "datum", "zusatz.periode", "nurMonat", "zusatz.periode"),
                "WARNUNG", "Monat fehlt.", true);
        assertThat(new Konfiguration(1, einstellungen(), List.of(art), List.of(), List.of(folge, erwartet)).pruefen())
                .isEmpty();

        var falscheFolge = new Regel("folge", "RECHNUNG", "FOLGE",
                Map.of("feld", "zusatz.periode", "zeitraum", "GESAMT"),
                "WARNUNG", "Folge prüfen.", true);
        var falschesErwartet = new Regel("erwartet", "RECHNUNG", "ERWARTET",
                Map.of("je", "MONAT", "datum", "zusatz.jahr", "nurMonat", "zusatz.unbekannt"),
                "WARNUNG", "Monat fehlt.", true);
        assertThat(new Konfiguration(1, einstellungen(), List.of(art), List.of(),
                List.of(falscheFolge, falschesErwartet)).pruefen())
                .extracting(Konfiguration.Befund::code).contains("REGEL_FELD_UNGUELTIG");

        var ohneNr = new Konfiguration.Art("RECHNUNG", "Rechnung", null, List.of("finanz"),
                List.of("titel"), "{\"type\":\"object\"}", "NORMAL",
                new Konfiguration.Aufbewahrung(null, null), false, true, true,
                List.of("^R\\\\d+$"), null, null, true, List.of(), null, 0);
        var musterFolge = new Regel("folge", "RECHNUNG", "FOLGE",
                Map.of("muster", true, "zeitraum", "GESCHAEFTSJAHR"),
                "WARNUNG", "Folge prüfen.", true);
        assertThat(new Konfiguration(1, einstellungen(), List.of(ohneNr), List.of(), List.of(musterFolge)).pruefen())
                .extracting(Konfiguration.Befund::code).contains("REGEL_MUSTER_UNGUELTIG");
    }

    @Test
    void geschaeftsjahrNurAlsFeldEinerFolge() {
        var art = art("MERKBLATT", "NORMAL", true, false);
        var folge = new Regel("folge", "MERKBLATT", "FOLGE",
                Map.of("feld", "geschaeftsjahr", "zeitraum", Map.of("feld", "geschaeftsjahr")),
                "WARNUNG", "Folge prüfen.", true);
        assertThat(new Konfiguration(1, einstellungen(), List.of(art), List.of(), List.of(folge)).pruefen()).isEmpty();

        var eindeutig = new Regel("eindeutig", "MERKBLATT", "EINDEUTIG", Map.of("felder", List.of("geschaeftsjahr")),
                "WARNUNG", "Doppelt.", true);
        assertThat(new Konfiguration(1, einstellungen(), List.of(art), List.of(), List.of(eindeutig)).pruefen())
                .extracting(Konfiguration.Befund::code).contains("REGEL_FELD_UNGUELTIG");
    }

    @Test
    void beruecksichtigtRegelnBeimVersionsvergleichUndProtokolliertIhreAenderungen() {
        var art = art("MERKBLATT", "NORMAL", true, false);
        var vorherRegel = new Regel("pflicht", "MERKBLATT", "PFLICHT_WENN",
                Map.of("wenn", Map.of("feld", "titel", "gleich", "Test"), "dann", List.of("datum")),
                "WARNUNG", "Meldung.", true);
        var nachherRegel = new Regel("pflicht", "MERKBLATT", "PFLICHT_WENN",
                Map.of("wenn", Map.of("feld", "titel", "gleich", "Test"), "dann", List.of("datum")),
                "FEHLER", "Meldung.", true);
        var vorher = new Konfiguration(1, einstellungen(), List.of(art), List.of(), List.of(vorherRegel));
        var nachher = new Konfiguration(2, einstellungen(), List.of(art), List.of(), List.of(nachherRegel));

        assertThat(vorher.gleicherInhalt(nachher)).isFalse();
        assertThat(vorher.aenderungen(nachher))
                .contains(new Konfiguration.Aenderung("regeln.pflicht.schwere", "WARNUNG", "FEHLER"));
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

    /** design.md «Grenzen gegen zu lange Prompts»: Summe der Prompt-Texte höchstens 24'000 Zeichen (Req 6.6). */
    @Test
    void summeDerPromptTexteHoechstens24000Zeichen() {
        List<KlassifikationWert> werte = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            werte.add(new KlassifikationWert("w" + i, "W", "x".repeat(1000), i, true));
        }
        var kiKlasse = new Klassifikation("fachgebiet", "F", null, werte, false, true, true, false, true);
        var ohneKi = new Klassifikation("fachgebiet", "F", null, werte, false, false, true, false, true);
        var texte = new Konfiguration.KiTexte("r".repeat(8000), "f".repeat(8000), true);

        // 8'000 + 8'000 + 8 × (1 + 1'000) + 1 = 24'009
        assertThat(new Konfiguration(1, einstellungen(), List.of(), List.of(kiKlasse), List.of(), texte).pruefen())
                .containsExactly(new Konfiguration.Befund("kiTexte", "TEXTE_ZU_LANG",
                        "Die Texte im KI-Prompt überschreiten zusammen 24'000 Zeichen."));
        // Klassifikationen ohne KI-Vorschlag gelangen nicht in den Prompt
        assertThat(new Konfiguration(1, einstellungen(), List.of(), List.of(ohneKi), List.of(), texte).pruefen())
                .isEmpty();
        var knapp = new Konfiguration.KiTexte("r".repeat(8000), "f".repeat(7990), true);
        assertThat(new Konfiguration(1, einstellungen(), List.of(), List.of(kiKlasse), List.of(), knapp).pruefen())
                .isEmpty();
    }

    @Test
    void erkenntAbbildOhneKiTexte() {
        assertThat(new Konfiguration(1, einstellungen(), List.of()).ohneKiTexte()).isTrue();
        assertThat(new Konfiguration(1, einstellungen(), List.of(), List.of(), List.of(),
                new Konfiguration.KiTexte("", "", false)).ohneKiTexte()).isFalse();
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
