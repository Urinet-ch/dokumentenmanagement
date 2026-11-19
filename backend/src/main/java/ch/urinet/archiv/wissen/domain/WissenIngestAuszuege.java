package ch.urinet.archiv.wissen.domain;

/** Prüft KI-Auszüge gegen den Volltext der angegebenen PDF-Seite, normalisiert wie {@link WissensPruefung}. */
public final class WissenIngestAuszuege {

    private WissenIngestAuszuege() {
    }

    public static boolean belegt(String seitentext, String auszug) {
        if (seitentext == null || auszug == null || auszug.isBlank()
                || auszug.codePointCount(0, auszug.length()) > WissensPruefung.AUSZUG_MAX_ZEICHEN) {
            return false;
        }
        String seite = WissensPruefung.auszugNormalisieren(seitentext);
        String zitat = WissensPruefung.auszugNormalisieren(auszug);
        return !zitat.isBlank() && seite.contains(zitat);
    }
}
