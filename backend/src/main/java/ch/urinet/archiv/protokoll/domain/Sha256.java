package ch.urinet.archiv.protokoll.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** SHA-256 und Hex-Darstellung (kleingeschrieben, 64 Zeichen) für Manifeste (Req 15.1, 15.2). */
public final class Sha256 {

    public static final Pattern HEX = Pattern.compile("^[0-9a-f]{64}$");

    private Sha256() {
    }

    public static MessageDigest neu() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt in der JVM", e);
        }
    }

    public static byte[] von(byte[]... teile) {
        MessageDigest md = neu();
        for (byte[] teil : teile) {
            md.update(teil);
        }
        return md.digest();
    }

    public static String hex(byte[] werte) {
        return HexFormat.of().formatHex(werte);
    }

    public static String hexVon(byte[]... teile) {
        return hex(von(teile));
    }

    /** Prüft und liefert einen Hex-Wert; {@code null} bleibt {@code null}. */
    public static String gueltig(String hex, String feld) {
        if (hex != null && !HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException(feld + " ist keine SHA-256 (64 Hex-Zeichen)");
        }
        return hex;
    }
}
