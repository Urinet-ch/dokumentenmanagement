package ch.urinet.archiv.dokument.domain;

import java.text.Normalizer;

/**
 * Dateiname aus dem Upload, nur zur Anzeige im Ergebnis (design.md «Upload», «Umsetzung 2.4»): ohne Pfadanteile,
 * höchstens {@value #MAX_ZEICHEN} Zeichen, leer → {@value #ERSATZ}. Ein Name mit Steuerzeichen (Unicode Cc) oder
 * unsichtbaren Formatzeichen (Cf, z. B. die Richtungsumkehr U+202E; ausser den Verbindern U+200C/U+200D in Emoji) ist
 * nicht {@link #gueltig()}: Die Datei wird abgelehnt, {@link #anzeige()} enthält diese Zeichen nicht. Der Name gelangt
 * nie in einen Pfad, einen S3-Schlüssel, die Datenbank oder das Log.
 */
public record DateiName(String anzeige, boolean gueltig) {

    public static final int MAX_ZEICHEN = 255;
    public static final String ERSATZ = "datei";

    public static DateiName aus(String roh) {
        if (roh == null) {
            return new DateiName(ERSATZ, true);
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
        return new DateiName(anzeige.isEmpty() || anzeige.equals(".") || anzeige.equals("..") ? ERSATZ : anzeige,
                gueltig);
    }

    private static boolean verboten(int c) {
        int typ = Character.getType(c);
        return typ == Character.CONTROL || typ == Character.SURROGATE
                || typ == Character.FORMAT && c != 0x200C && c != 0x200D;
    }
}
