package ch.urinet.archiv.importe.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Spaltennamen der Kopfzeile in ihrer Reihenfolge; Namen dürfen doppelt vorkommen (21.CRM: {@code Sekretär(in)}),
 * darum gilt jede Zuordnung nach Position. Der {@link #hash()} vergleicht die Kopfzeile mit der gespeicherten
 * Zuordnung (Req 13.2).
 */
public record Kopfzeile(List<String> namen) {

    private static final byte TRENNER = 0x1F;

    public Kopfzeile {
        namen = List.copyOf(namen);
    }

    public int spalten() {
        return namen.size();
    }

    /**
     * SHA-256 (Hex) über die normalisierten Namen in ihrer Reihenfolge: Unicode NFKC, ohne Leerraum am Rand,
     * Kleinschreibung. Eine andere Reihenfolge, ein zusätzlicher oder fehlender Name ergibt einen anderen Hash.
     */
    public String hash() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt in der JVM", e);
        }
        for (String name : namen) {
            digest.update(normalisiert(name).getBytes(StandardCharsets.UTF_8));
            digest.update(TRENNER);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Erste Position des Namens (normalisiert verglichen), sonst -1. */
    public int position(String name) {
        String gesucht = normalisiert(name);
        for (int i = 0; i < namen.size(); i++) {
            if (normalisiert(namen.get(i)).equals(gesucht)) {
                return i;
            }
        }
        return -1;
    }

    static String normalisiert(String name) {
        return Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }
}
