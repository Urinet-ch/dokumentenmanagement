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

    /**
     * Höchstlänge einer Datenzeile, gemessen wie ihre Werte als JSON in {@code import_zeile.daten} ({@link #jsonZeichen}:
     * Steuerzeichen sechsfach, {@code "} und {@code \} doppelt, sonst einfach; ohne Anführungszeichen und Kommas
     * zwischen den Werten). Eine längere Zeile liest der Leser nur bis zur Grenze, sie ist «fehlerhaft» und ihre Werte
     * werden nicht gespeichert (Speicher, Entscheid Fabian 11.11.2026: höchstens ~32 MiB Heap je Import).
     */
    public static final int MAX_ZEILE = 256 * 1024;

    private CsvTabelle() {
    }

    /**
     * Zeichen, die {@code zeichen} in einem JSON-String belegt (wie Jackson schreibt): Steuerzeichen unter U+0020 als
     * Unicode-Escape 6 (obere Schranke, {@code \n} u. ä. brauchen 2), {@code "} und {@code \} 2, sonst 1.
     */
    public static int jsonZeichen(char zeichen) {
        if (zeichen < 0x20) {
            return 6;
        }
        return zeichen == '"' || zeichen == '\\' ? 2 : 1;
    }

    /** Obere Schranke für die Länge der Werte als JSON-Array ({@code ["…","…"]}); {@code null} → 0. */
    public static long jsonZeichen(List<String> werte) {
        if (werte == null) {
            return 0;
        }
        long summe = 2;
        for (String wert : werte) {
            summe += 3;
            if (wert != null) {
                for (int i = 0; i < wert.length(); i++) {
                    summe += jsonZeichen(wert.charAt(i));
                }
            }
        }
        return summe;
    }

    /**
     * Eine Datenzeile mit ihrer Nummer und den Werten in Spaltenreihenfolge. {@code zuLang}: Die Zeile ist länger als
     * {@link #MAX_ZEILE}; der Leser hat sie nur bis zur Grenze gelesen, {@code werte} ist dann leer.
     */
    public record Zeile(int nr, List<String> werte, boolean zuLang) {
        public Zeile {
            werte = List.copyOf(werte);
        }

        public Zeile(int nr, List<String> werte) {
            this(nr, werte, false);
        }
    }
}
