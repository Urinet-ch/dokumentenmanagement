package ch.urinet.archiv.importe.domain;

import java.util.List;
import java.util.Objects;

/**
 * Prüft eine Datenzeile vor dem Abgleich (Req 13.10, design.md «Fehlerhaft»): falsche Spaltenzahl nach dem Parsen,
 * ein Feld über der Feldgrenze, mit Zuordnung ein leeres Pflichtfeld ({@code name} bzw. {@code titel}) → «fehlerhaft»;
 * im CRM gelöschte Zeilen ({@code geloescht = true}) → übersprungen. Die Meldungen nennen nur Spalten und Zahlen, nie
 * Werte.
 */
public final class ZeilenPruefung {

    private ZeilenPruefung() {
    }

    /** Strukturprüfung ohne Zuordnung: Spaltenzahl und Feldgrenze. */
    public static Befund struktur(List<String> werte, int spalten, int feldGrenze) {
        if (werte.size() != spalten) {
            return Befund.fehler("Spaltenzahl " + werte.size() + " statt " + spalten + " wie in der Kopfzeile");
        }
        for (int i = 0; i < werte.size(); i++) {
            String wert = werte.get(i);
            if (zuLang(wert, feldGrenze)) {
                return new Befund(Ergebnis.FEHLER, "Feld in Spalte " + (i + 1) + " länger als " + feldGrenze
                        + " Zeichen", true);
            }
        }
        return Befund.OFFEN;
    }

    /** Kopfzeile mit einem Spaltennamen über der Feldgrenze: Das Archiv lehnt die Datei ab (Req 13.10). */
    public static boolean kopfZuLang(Kopfzeile kopf, int feldGrenze) {
        return kopf.namen().stream().anyMatch(name -> zuLang(name, feldGrenze));
    }

    /** Länge in Unicode-Zeichen (Codepoints), nicht in UTF-16-Einheiten. */
    static boolean zuLang(String wert, int feldGrenze) {
        return wert != null && wert.length() > feldGrenze && wert.codePointCount(0, wert.length()) > feldGrenze;
    }

    /** Vollständige Prüfung mit gültiger Zuordnung; die Struktur zuerst, weil Positionen sonst nicht stimmen. */
    public static Befund pruefen(ImportArt art, Zuordnung zuordnung, List<String> werte, int spalten, int feldGrenze) {
        Befund struktur = struktur(werte, spalten, feldGrenze);
        if (struktur.ergebnis() != Ergebnis.OFFEN) {
            return struktur;
        }
        if (zuordnung.geloescht(werte)) {
            return Befund.UEBERSPRUNGEN;
        }
        if (!zuordnung.werte(art, werte).containsKey(art.pflichtfeld())) {
            return Befund.fehler(art == ImportArt.KONTAKT ? "Name fehlt" : "Titel fehlt");
        }
        return Befund.OFFEN;
    }

    /** Ergebnis der Prüfung; {@code OFFEN} wartet auf den Abgleich (Aufgabe 4.2). */
    public enum Ergebnis {
        OFFEN,
        FEHLER,
        UEBERSPRUNGEN
    }

    /** {@code werteVerwerfen}: Die Zeile hat ein Feld über der Feldgrenze; ihre Werte werden nicht gespeichert. */
    public record Befund(Ergebnis ergebnis, String meldung, boolean werteVerwerfen) {

        public static final Befund OFFEN = new Befund(Ergebnis.OFFEN, null, false);
        public static final Befund UEBERSPRUNGEN = new Befund(Ergebnis.UEBERSPRUNGEN, "im CRM gelöscht", false);

        public Befund {
            Objects.requireNonNull(ergebnis, "ergebnis");
        }

        static Befund fehler(String meldung) {
            return new Befund(Ergebnis.FEHLER, meldung, false);
        }
    }
}
