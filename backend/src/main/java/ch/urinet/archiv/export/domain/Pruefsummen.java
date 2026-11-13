package ch.urinet.archiv.export.domain;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * {@code pruefsummen.sha256} im Jahresexport (Spec 02, Req 7.1): je Datei im ZIP ausser dieser eine Zeile im Format
 * von {@code sha256sum} ({@code <hex>  <pfad>}, LF), damit {@code sha256sum -c pruefsummen.sha256} im entpackten
 * Ordner jede Datei prüft. Die Pfade enthalten nur ASCII ohne Leerzeichen ({@link ExportDateiname}).
 */
public final class Pruefsummen {

    public static final String DATEINAME = "pruefsummen.sha256";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private Pruefsummen() {
    }

    public static byte[] zeile(String sha256, String pfad) {
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Keine SHA-256 in Hex: " + pfad);
        }
        if (pfad == null || pfad.isEmpty() || pfad.chars().anyMatch(c -> c <= ' ' || c > '~')) {
            throw new IllegalArgumentException("Pfad nicht in ASCII ohne Leerzeichen");
        }
        return (sha256 + "  " + pfad + "\n").getBytes(StandardCharsets.US_ASCII);
    }
}
