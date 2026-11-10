package ch.urinet.archiv.ablage.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 als Hex in Kleinbuchstaben (Req 6.1); massgeblich für die Integrität, die Versions-ID ist Zusatz. */
public final class Pruefsumme {

    private Pruefsumme() {
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt in der JVM", e);
        }
    }

    public static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String von(byte[] inhalt) {
        MessageDigest digest = sha256();
        digest.update(inhalt);
        return hex(digest);
    }
}
