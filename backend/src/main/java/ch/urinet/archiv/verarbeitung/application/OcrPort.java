package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.domain.OcrEingabe;
import ch.urinet.archiv.verarbeitung.domain.OcrErgebnis;

import java.io.IOException;

/**
 * Driven Port der Verarbeitung (design.md «Verarbeitung», Req 5.1–5.3, 5.8–5.10; ADR-011, Req 16.13): HEIC → JPEG,
 * Bilder → PDF, OCRmyPDF zu PDF/A-2b ({@code --skip-text} bei vorhandener Textebene), Text mit {@code pdftotext},
 * Vorschaubilder. Adapter {@code OcrMyPdfAdapter}, Dev-Stub über {@code archiv.ocr.stub}.
 * <p>
 * Ein Lauf arbeitet in einem eigenen temporären Verzeichnis, das nach der Verwertung gelöscht wird. Er endet spätestens
 * nach {@code archiv.ocr.zeitgrenze} (unter der Zeitgrenze von {@code VERARBEITEN}); dann beendet der Adapter die
 * Prozesse ({@code destroyForcibly}) und wirft {@link ch.urinet.archiv.verarbeitung.domain.OcrFehlgeschlagen}. Die
 * Quellen bleiben unverändert (Req 5.8, 5.10).
 */
public interface OcrPort {

    /**
     * Verarbeitet die Quellen und übergibt das Ergebnis an {@code verwertung}, solange die Dateien bestehen.
     *
     * @throws ch.urinet.archiv.verarbeitung.domain.OcrFehlgeschlagen bei Zeitüberschreitung, Werkzeug- oder E/A-Fehler
     * @throws InterruptedException                                    nach einer Unterbrechung; laufende Prozesse sind
     *                                                                 dann beendet
     */
    <T> T verarbeiten(OcrEingabe eingabe, Verwertung<T> verwertung) throws InterruptedException;

    /** Verwertet das Ergebnis, z. B. lädt PDF/A und Vorschaubilder hoch; läuft vor dem Löschen des Verzeichnisses. */
    @FunctionalInterface
    interface Verwertung<T> {
        T verwerten(OcrErgebnis ergebnis) throws IOException, InterruptedException;
    }
}
