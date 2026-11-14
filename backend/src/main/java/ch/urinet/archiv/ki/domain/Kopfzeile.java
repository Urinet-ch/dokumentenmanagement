package ch.urinet.archiv.ki.domain;

/**
 * Kopfzeile «Titel – Art – Partner» eines Abschnitts genau wie im {@link Chunker} (Spec 02, design.md «Embeddings und
 * Hybridsuche»). Die Hybridsuche (Aufgabe 6.4) bildet damit den Text für den Reranker bei Treffern nur aus dem Volltext
 * und trennt beim Auszug den Inhalt eines Abschnitts von seiner Kopfzeile.
 */
public final class Kopfzeile {

    private Kopfzeile() {
    }

    /** Kopfzeile aus Titel, Name der Art und Partner; leere Teile entfallen, ohne alle Teile leer. */
    public static String von(String titel, String art, String partner) {
        return Chunker.kopfzeile(new Chunker.Quelle(titel, art, partner, null, null, false));
    }

    /** Kopfzeile und Inhalt so verbunden, wie der {@link Chunker} einen Abschnitt bildet. */
    public static String mitInhalt(String kopf, String inhalt) {
        if (inhalt == null || inhalt.isEmpty()) {
            return kopf;
        }
        return kopf.isEmpty() ? inhalt : kopf + Chunker.TRENNER + inhalt;
    }

    /** Inhalt eines Abschnitts ohne die Kopfzeile {@code kopf}; passt sie nicht (geänderter Titel), der ganze Text. */
    public static String inhalt(String text, String kopf) {
        if (kopf.isEmpty()) {
            return text;
        }
        if (text.equals(kopf)) {
            return "";
        }
        return text.startsWith(kopf + Chunker.TRENNER) ? text.substring(kopf.length() + Chunker.TRENNER.length())
                : text;
    }
}
