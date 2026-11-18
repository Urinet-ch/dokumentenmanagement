package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.RegelVerletzt;
import ch.urinet.archiv.konfiguration.application.KonfigurationVerwaltung;
import ch.urinet.archiv.konfiguration.application.RegelDatenPort;
import ch.urinet.archiv.konfiguration.application.RegelPruefung;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Regel;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class RegelnSchritte {

    private List<Konfiguration.Befund> befunde;
    private List<Regel.Parameter> typisierteRegeln = List.of();
    private List<RegelPruefung.Warnung> warnungen = List.of();
    private RegelVerletzt fehler;

    @Wenn("die vier Regeltypen der Konfiguration aufgebaut werden")
    public void regeltypenAufbauen() {
        List<Regel> regeln = List.of(
                new Regel("datum_pflicht", "RECHNUNG", "PFLICHT_WENN",
                        Map.of("wenn", Map.of("feld", "belegnummer", "gleich", "R1"), "dann", List.of("datum")),
                        "FEHLER", "Belegdatum fehlt.", true),
                new Regel("nummer_eindeutig", "RECHNUNG", "EINDEUTIG", Map.of("felder", List.of("belegnummer")),
                        "FEHLER", "Nummer bereits erfasst.", true),
                new Regel("nummer_folge", "RECHNUNG", "FOLGE",
                        Map.of("feld", "zusatz.nummer", "zeitraum", "GESCHAEFTSJAHR"),
                        "WARNUNG", "Nummernfolge prüfen.", true),
                new Regel("beleg_erwartet", "RECHNUNG", "ERWARTET",
                        Map.of("je", "MONAT", "datum", "BELEGDATUM"),
                        "WARNUNG", "Monatsbeleg fehlt.", true));
        typisierteRegeln = regeln.stream().map(Regel::typisierteParameter).toList();
    }

    @Wenn("eine aktive Pflichtregel mit Schwere «{word}» und fehlendem Datum geprüft wird")
    public void pflichtregelPruefen(String schwere) {
        pruefenPflichtregel(schwere, null);
    }

    @Wenn("eine bereits verletzte Pflichtregel mit unveränderten Metadaten geprüft wird")
    public void bestehendePflichtverletzungPruefen() {
        pruefenPflichtregel("FEHLER", Map.of("belegnummer", "R1"));
    }

    private void pruefenPflichtregel(String schwere, Map<String, Object> vorher) {
        Regel regel = new Regel("datum_pflicht", "RECHNUNG", "PFLICHT_WENN",
                Map.of("wenn", Map.of("feld", "belegnummer", "gleich", "R1"), "dann", List.of("datum")),
                schwere, "Belegdatum fehlt.", true);
        fehler = null;
        warnungen = List.of();
        try {
            warnungen = pruefung(regel, (art, alle, felder, werte, id) -> false)
                    .pruefen("RECHNUNG", Map.of("belegnummer", "R1"), vorher, UUID.randomUUID());
        } catch (RegelVerletzt e) {
            fehler = e;
        }
    }

    @Dann("wird der Vorgang mit «{word}» abgelehnt")
    public void abgelehnt(String code) {
        assertThat(fehler).isNotNull();
        assertThat(fehler.code()).isEqualTo(Fehlercode.valueOf(code));
        assertThat(warnungen).isEmpty();
    }

    @Dann("enthält die Antwort die Warnung «{word}»")
    public void warnung(String code) {
        assertThat(fehler).isNull();
        assertThat(warnungen).extracting(RegelPruefung.Warnung::code).contains(code);
    }

    @Dann("wird der Vorgang nicht abgelehnt")
    public void nichtAbgelehnt() {
        assertThat(fehler).isNull();
    }

    @Dann("sind alle vier Regeltypen typisiert")
    public void alleRegeltypenTypisiert() {
        assertThat(typisierteRegeln).hasSize(4);
        assertThat(typisierteRegeln.get(0)).isInstanceOf(Regel.PflichtWenn.class);
        assertThat(typisierteRegeln.get(1)).isInstanceOf(Regel.Eindeutig.class);
        assertThat(typisierteRegeln.get(2)).isInstanceOf(Regel.Folge.class);
        assertThat(typisierteRegeln.get(3)).isInstanceOf(Regel.Erwartet.class);
    }

    @Wenn("eine Regel ein nicht vorhandenes Zusatzfeld referenziert")
    public void unbekanntesZusatzfeld() {
        Regel regel = new Regel("feld_unbekannt", "RECHNUNG", "PFLICHT_WENN",
                Map.of("wenn", Map.of("feld", "zusatz.unbekannt", "gleich", "x"), "dann", List.of("datum")),
                "FEHLER", "Unbekanntes Feld.", true);
        Konfiguration.Art art = new Konfiguration.Art("RECHNUNG", "Rechnung", null, List.of(), List.of(), null,
                "NORMAL", new Konfiguration.Aufbewahrung(null, "BELEGDATUM"), false, true);
        befunde = new Konfiguration(1, new Konfiguration.Einstellungen("12-31", null), List.of(art), List.of(),
                List.of(regel)).pruefen();
    }

    @Dann("wird die Test-Konfiguration mit Befund «{word}» abgelehnt")
    public void konfigurationAbgelehnt(String code) {
        assertThat(befunde).extracting(Konfiguration.Befund::code).contains(code);
    }

    private static RegelPruefung pruefung(Regel regel, RegelDatenPort daten) {
        Konfiguration abbild = new Konfiguration(1, new Konfiguration.Einstellungen("12-31", null), List.of(),
                List.of(), List.of(regel));
        KonfigurationVerwaltung verwaltung = new KonfigurationVerwaltung(null, null, null, null, null, null,
                null, null, null, null) {
            @Override
            public Konfiguration aktuell() {
                return abbild;
            }
        };
        return new RegelPruefung(verwaltung, daten, null);
    }
}
