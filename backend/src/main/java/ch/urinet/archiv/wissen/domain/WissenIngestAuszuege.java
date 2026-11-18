package ch.urinet.archiv.wissen.domain;

import java.text.Normalizer;

/** Prüft KI-Auszüge gegen den Volltext der angegebenen PDF-Seite. */
public final class WissenIngestAuszuege {

    private WissenIngestAuszuege() {
    }

    public static boolean belegt(String seitentext, String auszug) {
        if (seitentext == null || auszug == null || auszug.isBlank()
                || auszug.codePointCount(0, auszug.length()) > WissensPruefung.AUSZUG_MAX_ZEICHEN) {
            return false;
        }
        String seite = normalisieren(seitentext);
        String zitat = normalisieren(auszug);
        return !zitat.isEmpty() && seite.contains(zitat);
    }

    private static String normalisieren(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("(?U)\\s+", " ").strip();
    }
}
