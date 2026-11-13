package ch.urinet.archiv.dokument.domain;

import java.text.Normalizer;
import java.util.Optional;

/**
 * Dateiname aus dem Upload (design.md «Upload», «Dateiname»; Req 3.9, 3.10): NFC, ohne Pfadanteile, höchstens
 * {@value #MAX_ZEICHEN} Zeichen, aussen ohne Leerraum; leer, {@code .} oder {@code ..} → {@link #ersatz()} mit der
 * Anzeige {@value #ERSATZ}. Ein Name mit Steuerzeichen (Unicode Cc, Cs), Zeilen- oder Absatztrennern (Zl, Zp) oder
 * unsichtbaren Formatzeichen (Cf, z. B. die Richtungsumkehr U+202E; ausser den Verbindern U+200C/U+200D in Emoji) ist
 * nicht {@link #gueltig()}: Die Datei wird abgelehnt, {@link #anzeige()} enthält diese Zeichen nicht. In
 * {@code dokument.dateiname} gelangt nur {@link #speicherbar()}, nie der Ersatz; der Name gelangt nie in einen Pfad,
 * einen S3-Schlüssel, das Log oder an die KI (Req 3.12).
 *
 * @param ersatz {@code true}, wenn der Name fehlt oder nach dem Bereinigen leer ist und {@link #anzeige()} darum
 *               {@value #ERSATZ} lautet; eine echte Datei namens {@code datei} ist kein Ersatz
 */
public record DateiName(String anzeige, boolean gueltig, boolean ersatz) {

    public static final int MAX_ZEICHEN = 255;
    public static final String ERSATZ = "datei";

    public static DateiName aus(String roh) {
        if (roh == null) {
            return new DateiName(ERSATZ, true, true);
        }
        String name = Normalizer.normalize(roh, Normalizer.Form.NFC);
        int trenner = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(trenner + 1);
        StringBuilder sauber = new StringBuilder();
        boolean gueltig = true;
        int zeichen = 0;
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            i += Character.charCount(c);
            if (verboten(c)) {
                gueltig = false;
            } else if (zeichen < MAX_ZEICHEN) {
                sauber.appendCodePoint(c);
                zeichen++;
            }
        }
        String anzeige = sauber.toString().strip();
        boolean ersatz = anzeige.isEmpty() || anzeige.equals(".") || anzeige.equals("..");
        return new DateiName(ersatz ? ERSATZ : anzeige, gueltig, ersatz);
    }

    /** Der Name für {@code dokument.dateiname}; leer beim Ersatz oder bei einem ungültigen Namen (Req 3.9, 11.12). */
    public Optional<String> speicherbar() {
        return gueltig && !ersatz ? Optional.of(anzeige) : Optional.empty();
    }

    private static boolean verboten(int c) {
        int typ = Character.getType(c);
        return typ == Character.CONTROL || typ == Character.SURROGATE || typ == Character.LINE_SEPARATOR
                || typ == Character.PARAGRAPH_SEPARATOR
                || typ == Character.FORMAT && c != 0x200C && c != 0x200D;
    }
}
