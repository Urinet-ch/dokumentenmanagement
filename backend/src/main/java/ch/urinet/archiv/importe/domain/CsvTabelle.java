package ch.urinet.archiv.importe.domain;

import java.util.List;

/**
 * Grenzen einer CSV-Datei und ihre Datenzeilen nach RFC 4180. Die Kopfzeile ist Datensatz 1, die erste Datenzeile hat
 * die Nummer 2 (wie in einer Tabellenkalkulation ohne Leerzeilen). Die Datei wird nie als Ganzes gehalten, sondern
 * Zeile für Zeile gelesen (Entscheid Fabian 11.11.2026).
 */
public final class CsvTabelle {

    /** Höchstzahl der Datenzeilen ohne Kopfzeile (Req 13.10). */
    public static final int MAX_ZEILEN = 10_000;

    /**
     * Höchstzahl der Spalten eines Datensatzes; schützt den Speicher schon vor dem Parsen (der 21.CRM-Export hat 151).
     * Eine Datei mit einem breiteren Datensatz lehnt das Archiv ab.
     */
    public static final int MAX_SPALTEN = 1_000;

    /**
     * Höchstlänge eines Spaltennamens in Zeichen (Codepoints); der längste Name im 21.CRM-Beispielexport hat 48. Eine
     * Kopfzeile mit längerem Namen lehnt das Archiv vor dem Parsen ab (Speicher, Entscheid Fabian 11.11.2026).
     */
    public static final int MAX_KOPF_NAME = 256;

    /**
     * Höchstlänge der ganzen Kopfzeile in Zeichen (Codepoints, mit Trenn- und Anführungszeichen, ohne Zeilenende);
     * der 21.CRM-Beispielexport hat 2'646 (151 Spalten). Grössere Kopfzeilen lehnt das Archiv vor dem Parsen ab.
     */
    public static final int MAX_KOPF = 64 * 1024;

    private CsvTabelle() {
    }

    /** Eine Datenzeile mit ihrer Nummer und den Werten in Spaltenreihenfolge. */
    public record Zeile(int nr, List<String> werte) {
        public Zeile {
            werte = List.copyOf(werte);
        }
    }
}
