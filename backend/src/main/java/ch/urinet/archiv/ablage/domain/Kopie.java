package ch.urinet.archiv.ablage.domain;

import java.util.regex.Pattern;

/** Ergebnis einer stromweisen Kopie aus dem Object Storage (Jahresexport): SHA-256 (hex) und Grösse der Bytes. */
public record Kopie(String sha256, long groesse) {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public Kopie {
        if (sha256 == null || !SHA256.matcher(sha256).matches() || groesse < 0) {
            throw new IllegalArgumentException("Ungültige Kopie");
        }
    }
}
