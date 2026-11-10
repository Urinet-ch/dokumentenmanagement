package ch.urinet.archiv.ablage.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Schlüssel einer Dokumentdatei ohne Präfix (design.md «Ablage»): {@code <yyyy>/<docId>/<nr>-original.<ext>},
 * {@code <yyyy>/<docId>/<nr>-arbeitsfassung.jpg}, {@code <yyyy>/<docId>/pdfa.pdf}; {@code <yyyy>} ist das Jahr des
 * Eingangs. Unter {@code eingang/} und {@code archiv/} gelten dieselben Namen.
 */
public record DateiSchluessel(int jahr, UUID docId, DateiRolle rolle, int nr, String endung) {

    private static final Pattern ENDUNG = Pattern.compile("^[a-z0-9]{1,10}$");

    public DateiSchluessel {
        Objects.requireNonNull(docId, "docId");
        Objects.requireNonNull(rolle, "rolle");
        if (jahr < 2000 || jahr > 9999) {
            throw new IllegalArgumentException("Ungültiges Eingangsjahr: " + jahr);
        }
        if (endung == null || !ENDUNG.matcher(endung).matches()) {
            throw new IllegalArgumentException("Ungültige Endung");
        }
        switch (rolle) {
            case ORIGINAL -> pruefeNr(nr);
            case ARBEITSFASSUNG -> {
                pruefeNr(nr);
                if (!endung.equals("jpg")) {
                    throw new IllegalArgumentException("Arbeitsfassung ist immer jpg");
                }
            }
            case PDFA -> {
                if (nr != 0 || !endung.equals("pdf")) {
                    throw new IllegalArgumentException("PDF/A hat keine Nummer und die Endung pdf");
                }
            }
        }
    }

    public static DateiSchluessel original(int jahr, UUID docId, int nr, String endung) {
        return new DateiSchluessel(jahr, docId, DateiRolle.ORIGINAL, nr, endung);
    }

    public static DateiSchluessel arbeitsfassung(int jahr, UUID docId, int nr) {
        return new DateiSchluessel(jahr, docId, DateiRolle.ARBEITSFASSUNG, nr, "jpg");
    }

    public static DateiSchluessel pdfa(int jahr, UUID docId) {
        return new DateiSchluessel(jahr, docId, DateiRolle.PDFA, 0, "pdf");
    }

    /** Ordner aller Dateien eines Dokuments, z. B. {@code eingang/2026/<docId>/}. */
    public static String ordner(Ablageort ort, int jahr, UUID docId) {
        return ort.praefix() + "/" + jahr + "/" + Objects.requireNonNull(docId, "docId") + "/";
    }

    public String pfad(Ablageort ort) {
        return ordner(ort, jahr, docId) + name();
    }

    public String name() {
        return switch (rolle) {
            case ORIGINAL -> nr + "-original." + endung;
            case ARBEITSFASSUNG -> nr + "-arbeitsfassung.jpg";
            case PDFA -> "pdfa.pdf";
        };
    }

    private static void pruefeNr(int nr) {
        if (nr < 1 || nr > 9999) {
            throw new IllegalArgumentException("Ungültige Nummer: " + nr);
        }
    }
}
