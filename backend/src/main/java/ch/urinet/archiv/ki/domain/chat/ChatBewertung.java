package ch.urinet.archiv.ki.domain.chat;

import java.time.Instant;

/** Bewertung einer gespeicherten Chat-Antwort (Spec 02, Requirement 15.2–15.4). */
public record ChatBewertung(long nachrichtId, int sterne, Grund grund, String freitext, Instant erstellt,
                            Instant geaendert) {

    public static final int MAX_FREITEXT = 500;

    public enum Grund {
        NICHT_BEANTWORTET,
        FALSCH,
        UNVOLLSTAENDIG,
        QUELLE,
        ZU_LANGSAM,
        ANDERES
    }

    public ChatBewertung {
        if (nachrichtId < 1 || sterne < 1 || sterne > 5 || erstellt == null || geaendert == null) {
            throw new IllegalArgumentException("Bewertung ungültig");
        }
        freitext = bereinigen(freitext);
        if (freitext != null && freitext.codePointCount(0, freitext.length()) > MAX_FREITEXT) {
            throw new IllegalArgumentException("Bewertung ungültig");
        }
    }

    private static String bereinigen(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder bereinigt = new StringBuilder(text.length());
        text.codePoints().filter(codepoint -> !Character.isISOControl(codepoint)).forEach(bereinigt::appendCodePoint);
        return bereinigt.toString();
    }
}
