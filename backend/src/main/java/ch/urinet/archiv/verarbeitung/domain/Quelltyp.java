package ch.urinet.archiv.verarbeitung.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Typ einer Quelle der Verarbeitung (Req 5.1), am Inhalt erkannt (Tika-Detektor, Req 3.3), nie an der Endung oder am
 * Dateinamen. Die Endung bestimmt nur den Namen der Kopie im Arbeitsverzeichnis.
 */
public enum Quelltyp {
    PDF("pdf"),
    JPEG("jpg"),
    PNG("png"),
    TIFF("tiff"),
    HEIC("heic");

    private final String endung;

    Quelltyp(String endung) {
        this.endung = endung;
    }

    public String endung() {
        return endung;
    }

    public boolean istBild() {
        return this != PDF;
    }

    /** Medientyp aus dem Tika-Detektor, z. B. {@code image/heic}; Parameter nach {@code ;} zählen nicht. */
    public static Optional<Quelltyp> ausMedientyp(String medientyp) {
        if (medientyp == null) {
            return Optional.empty();
        }
        String typ = medientyp.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (typ) {
            case "application/pdf" -> Optional.of(PDF);
            case "image/jpeg" -> Optional.of(JPEG);
            case "image/png" -> Optional.of(PNG);
            case "image/tiff" -> Optional.of(TIFF);
            case "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence" -> Optional.of(HEIC);
            default -> Optional.empty();
        };
    }
}
