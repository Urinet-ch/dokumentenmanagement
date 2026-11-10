package ch.urinet.archiv.ablage.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Eine Datei für {@code archivieren}: Schlüssel, Versions-ID unter {@code eingang/} ({@code null} = neueste) und die
 * SHA-256 aus dem Eingang (Req 6.1), mit der die Kopie verglichen wird (Req 6.3).
 */
public record ZuArchivieren(DateiSchluessel schluessel, String eingangVersionId, String sha256) {

    private static final Pattern HEX = Pattern.compile("^[0-9a-f]{64}$");

    public ZuArchivieren {
        Objects.requireNonNull(schluessel, "schluessel");
        if (sha256 == null || !HEX.matcher(sha256).matches()) {
            throw new IllegalArgumentException("SHA-256 muss 64 Hex-Zeichen in Kleinbuchstaben haben");
        }
    }
}
