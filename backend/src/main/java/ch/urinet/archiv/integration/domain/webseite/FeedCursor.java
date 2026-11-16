package ch.urinet.archiv.integration.domain.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Position im Änderungs-Feed (Spec 03, Aufgabe 11.9, Req 14.3): {@code (xid, id)} des letzten gelieferten Eintrags.
 * Nach aussen ist der Cursor ein opaker Text (Base64url ohne Auffüllung); die Webseite reicht ihn unverändert weiter
 * und deutet ihn nie. Ein Eintrag gehört zum Cursor, wenn sein {@code (xid, id)} grösser ist.
 *
 * @param xid Transaktions-ID ({@code xid8} von PostgreSQL) des Eintrags, ab 0
 * @param id  Nummer des Eintrags innerhalb der Tabelle, ab 0 ({@code 0}: vor dem ersten Eintrag dieser Transaktion)
 */
public record FeedCursor(long xid, long id) {

    private static final Pattern TEXT = Pattern.compile("^v1\\.(\\d{1,18})\\.(\\d{1,18})$");
    private static final int MAX_LAENGE = 64;

    public FeedCursor {
        if (xid < 0 || id < 0) {
            throw new IllegalArgumentException("Cursor ausserhalb des Bereichs");
        }
    }

    /** Cursor vor allen Einträgen der Transaktionen ab {@code horizont}. */
    public static FeedCursor vor(long horizont) {
        return new FeedCursor(horizont, 0);
    }

    public String kodieren() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("v1." + xid + "." + id).getBytes(StandardCharsets.US_ASCII));
    }

    /** Liest den Cursor einer Anfrage; jede Abweichung ist 400 {@code ANFRAGE_UNGUELTIG}, ohne den Wert zu nennen. */
    public static FeedCursor lesen(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_LAENGE) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        try {
            String klar = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.US_ASCII);
            Matcher treffer = TEXT.matcher(klar);
            if (!treffer.matches()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            FeedCursor cursor = new FeedCursor(Long.parseLong(treffer.group(1)), Long.parseLong(treffer.group(2)));
            if (!cursor.kodieren().equals(text)) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            return cursor;
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }
}
