package ch.urinet.archiv.importe.domain;

import java.util.List;
import java.util.Objects;

/**
 * Gelesene CSV-Datei: erkannter Zeichensatz und Trennzeichen, Kopfzeile und Datenzeilen nach RFC 4180. Die Kopfzeile
 * ist Datensatz 1, die erste Datenzeile hat die Nummer 2 (wie in einer Tabellenkalkulation ohne Leerzeilen).
 */
public record CsvTabelle(String zeichensatz, char trennzeichen, Kopfzeile kopf, List<Zeile> zeilen) {

    /** Höchstzahl der Datenzeilen ohne Kopfzeile (Req 13.10). */
    public static final int MAX_ZEILEN = 10_000;

    /**
     * Höchstzahl der Spalten eines Datensatzes; schützt den Speicher schon vor dem Parsen (der 21.CRM-Export hat 151).
     * Eine Datei mit einem breiteren Datensatz lehnt das Archiv ab.
     */
    public static final int MAX_SPALTEN = 1_000;

    public CsvTabelle {
        Objects.requireNonNull(zeichensatz, "zeichensatz");
        Objects.requireNonNull(kopf, "kopf");
        zeilen = List.copyOf(zeilen);
    }

    /** Eine Datenzeile mit ihrer Nummer und den Werten in Spaltenreihenfolge. */
    public record Zeile(int nr, List<String> werte) {
        public Zeile {
            werte = List.copyOf(werte);
        }
    }
}
