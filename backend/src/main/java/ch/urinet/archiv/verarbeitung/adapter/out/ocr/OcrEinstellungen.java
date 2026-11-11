package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Einstellungen des OCR-Adapters ({@code archiv.ocr.*}).
 *
 * @param zeitgrenze         Zeitbudget eines ganzen Laufs, unter {@code archiv.auftrag.zeitgrenze.VERARBEITEN}
 * @param jobs               {@code ocrmypdf --jobs}; Stufe A 1 (seitenweise nacheinander, design.md «Stufen»)
 * @param arbeitsverzeichnis Basis der Laufverzeichnisse; nur {@code [A-Za-z0-9._/-]}, absolut
 * @param containerImage     nur Tests und Profil {@code lokal}: Werkzeuge per {@code docker run} in diesem Image statt
 *                           direkt; in {@code prod} abgelehnt
 * @param docker             Programm des Docker-Clients (nur mit {@code containerImage})
 * @param pfad               {@code PATH} der Werkzeuge im direkten Aufruf (OCRmyPDF startet Tesseract und Ghostscript)
 */
public record OcrEinstellungen(Duration zeitgrenze, int jobs, Path arbeitsverzeichnis,
                               Optional<String> containerImage, String docker, String pfad) {

    /** Keine Zeichen, die {@code docker -v} trennt ({@code :} {@code ,}), keine Leerzeichen oder Steuerzeichen. */
    private static final Pattern SICHERER_PFAD = Pattern.compile("/[A-Za-z0-9._/-]*");
    private static final Pattern IMAGE = Pattern.compile("[a-z0-9][a-z0-9._/:@-]*");

    public OcrEinstellungen {
        Objects.requireNonNull(zeitgrenze, "zeitgrenze");
        Objects.requireNonNull(arbeitsverzeichnis, "arbeitsverzeichnis");
        Objects.requireNonNull(containerImage, "containerImage");
        Objects.requireNonNull(docker, "docker");
        Objects.requireNonNull(pfad, "pfad");
        if (zeitgrenze.isNegative() || zeitgrenze.isZero()) {
            throw new IllegalArgumentException("archiv.ocr.zeitgrenze muss positiv sein");
        }
        if (jobs < 1) {
            throw new IllegalArgumentException("archiv.ocr.jobs muss mindestens 1 sein");
        }
        arbeitsverzeichnis = arbeitsverzeichnis.toAbsolutePath().normalize();
        if (!SICHERER_PFAD.matcher(arbeitsverzeichnis.toString()).matches()) {
            throw new IllegalArgumentException("archiv.ocr.arbeitsverzeichnis darf nur A-Z, a-z, 0-9, . _ / - enthalten");
        }
        containerImage = containerImage.filter(image -> !image.isBlank());
        if (containerImage.isPresent() && !IMAGE.matcher(containerImage.get()).matches()) {
            throw new IllegalArgumentException("archiv.ocr.container-image ungültig");
        }
    }

    /** Zeitbudget des Laufs muss unter der Zeitgrenze des Auftragstyps liegen (Pflichten der Handler, PR #41). */
    public static void pruefeZeitgrenze(Duration ocr, Duration verarbeiten) {
        if (ocr.compareTo(verarbeiten) >= 0) {
            throw new IllegalStateException("archiv.ocr.zeitgrenze (" + ocr + ") muss unter "
                    + "archiv.auftrag.zeitgrenze.VERARBEITEN (" + verarbeiten + ") liegen");
        }
    }
}
