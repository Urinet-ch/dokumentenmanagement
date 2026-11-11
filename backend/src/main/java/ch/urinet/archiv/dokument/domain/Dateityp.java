package ch.urinet.archiv.dokument.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Beim Upload angenommene Dateitypen (Req 3.1), erkannt am Inhalt (Req 3.3); die Endung bildet nur den S3-Schlüssel
 * ({@code <nr>-original.<endung>}, design.md «Ablage»), nie der Dateiname des Benutzers.
 */
public enum Dateityp {
    PDF("application/pdf", "pdf"),
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    HEIC("image/heic", "heic"),
    HEIF("image/heif", "heif"),
    TIFF("image/tiff", "tif");

    private final String mime;
    private final String endung;

    Dateityp(String mime, String endung) {
        this.mime = mime;
        this.endung = endung;
    }

    public String mime() {
        return mime;
    }

    public String endung() {
        return endung;
    }

    /** Typ zum erkannten Medientyp; Parameter wie {@code ; charset=…} zählen nicht. */
    public static Optional<Dateityp> ausMime(String erkannt) {
        if (erkannt == null) {
            return Optional.empty();
        }
        int ende = erkannt.indexOf(';');
        String basis = (ende < 0 ? erkannt : erkannt.substring(0, ende)).trim();
        return Arrays.stream(values()).filter(t -> t.mime.equalsIgnoreCase(basis)).findFirst();
    }
}
