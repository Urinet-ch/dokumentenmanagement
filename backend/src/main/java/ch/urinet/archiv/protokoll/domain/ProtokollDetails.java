package ch.urinet.archiv.protokoll.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Inhalt der Spalte {@code details} (jsonb): nur Grund, SHA-256 und Fehlercode, nie Dokumentinhalte (Req 10.2, 16.7).
 * Jedes Feld darf fehlen ({@code null}).
 */
public record ProtokollDetails(String grund, String sha256, String fehlercode) {

    public static final ProtokollDetails KEINE = new ProtokollDetails(null, null, null);

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern FEHLERCODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    public ProtokollDetails {
        if (sha256 != null) {
            sha256 = sha256.toLowerCase(Locale.ROOT);
            if (!SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("sha256 ist keine SHA-256-Prüfsumme in Hex");
            }
        }
        if (fehlercode != null && !FEHLERCODE.matcher(fehlercode).matches()) {
            throw new IllegalArgumentException("fehlercode ist kein Fehlercode");
        }
        if (grund != null && grund.isBlank()) {
            grund = null;
        }
    }

    public static ProtokollDetails fehlercode(String fehlercode) {
        return new ProtokollDetails(null, null, fehlercode);
    }

    public boolean leer() {
        return grund == null && sha256 == null && fehlercode == null;
    }
}
