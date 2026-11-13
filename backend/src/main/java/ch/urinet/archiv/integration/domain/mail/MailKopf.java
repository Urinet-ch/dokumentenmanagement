package ch.urinet.archiv.integration.domain.mail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Werte aus dem Kopf einer Mail, bereinigt, bevor sie in {@code mail_eingang} oder ins Log gelangen (Spec 02, Req 1.4,
 * 1.5): Der Kopf stammt von beliebigen Absendern und kann NUL, Steuerzeichen oder beliebige Längen enthalten, die
 * PostgreSQL ablehnt (NUL in {@code text}, Grenze des B-Tree-Index). Eine Message-ID verliert Steuer-, Format- und
 * Leerzeichen; ist sie danach länger als {@value #MAX_MESSAGE_ID} Zeichen, gilt {@code sha256:<Prüfsumme der ID>},
 * damit dieselbe Nachricht weiter dieselbe ID hat (Idempotenz).
 */
public final class MailKopf {

    public static final int MAX_MESSAGE_ID = 250;
    public static final int MAX_ABSENDER = 320;
    public static final int MAX_BETREFF = 1000;
    public static final String PRAEFIX_HASH = "sha256:";

    private MailKopf() {
    }

    /** Leer, wenn nach dem Bereinigen nichts bleibt. */
    public static Optional<String> messageId(String roh) {
        if (roh == null) {
            return Optional.empty();
        }
        StringBuilder sauber = new StringBuilder();
        roh.codePoints().filter(c -> !verboten(c) && !Character.isWhitespace(c)).forEach(sauber::appendCodePoint);
        String id = sauber.toString();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(id.codePointCount(0, id.length()) <= MAX_MESSAGE_ID ? id : PRAEFIX_HASH + sha256(id));
    }

    /** Steuer- und Formatzeichen werden Leerzeichen, höchstens {@code max} Zeichen; leer wird {@link Optional#empty()}. */
    public static Optional<String> wert(Optional<String> roh, int max) {
        return roh.map(MailHinweis::sauber).filter(s -> !s.isEmpty())
                .map(s -> s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max)));
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean verboten(int c) {
        int typ = Character.getType(c);
        return typ == Character.CONTROL || typ == Character.FORMAT || typ == Character.SURROGATE
                || typ == Character.LINE_SEPARATOR || typ == Character.PARAGRAPH_SEPARATOR
                || typ == Character.UNASSIGNED;
    }
}
