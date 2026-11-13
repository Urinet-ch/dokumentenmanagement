package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.domain.OcrEingabe;
import ch.urinet.archiv.verarbeitung.domain.OcrErgebnis;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

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

    /**
     * Text einer PDF für das Sprachmodell wie im Golden-Lauf: {@code pdftotext -layout -enc UTF-8}, Seiten durch
     * Seitenvorschub getrennt (Aufgabe 3.2; Req 7.1). Der Volltext für Suche und Index bleibt der aus
     * {@link #verarbeiten}. Das PDF bleibt unverändert.
     *
     * @param zeitgrenze Frist dieses Aufrufs (im KI-Auftrag {@code archiv.ki.render-zeitgrenze})
     * @throws ch.urinet.archiv.verarbeitung.domain.OcrFehlgeschlagen bei Zeitüberschreitung, Werkzeug- oder E/A-Fehler
     * @throws InterruptedException                                    nach einer Unterbrechung
     */
    String layoutText(Path pdf, Duration zeitgrenze) throws InterruptedException;

    /**
     * Rendert die ersten {@link Render#maxSeiten()} Seiten eines PDF und, wenn es mehr hat, zusätzlich die letzte Seite
     * ({@code pdftoppm}; graustufig als TIFF, farbig als PPM, beide verlustfrei) für den Swiss QR Code und den Bildweg
     * der KI (Aufgabe 3.2; Req 7.1, 7.11) und übergibt sie in Seitenreihenfolge an {@code verwertung}, solange die
     * Dateien bestehen. Jede Seite wird mit 300 dpi gerendert wie im Golden-Lauf, ausser sie hätte dann mehr als
     * {@link Render#pixelMax()} Pixel: dann mit so viel weniger dpi, dass sie hineinpasst. Eine Seite ohne lesbares Format fehlt in der Liste. Das PDF bleibt unverändert.
     *
     * @throws ch.urinet.archiv.verarbeitung.domain.OcrFehlgeschlagen bei Zeitüberschreitung, Werkzeug- oder E/A-Fehler
     * @throws InterruptedException                                    nach einer Unterbrechung
     */
    <T> T seitenbilder(Path pdf, Render render, Seitenverwertung<T> verwertung) throws InterruptedException;

    /**
     * @param maxSeiten  so viele Seiten von vorne (dazu die letzte)
     * @param farbig     farbig (Bildweg) oder in Graustufen ({@code -gray}, Swiss QR Code; ein Drittel des Speichers)
     * @param pixelMax   Grenze je Seite (Speicherbudget, design.md «Umsetzung 3.2»)
     * @param zeitgrenze Frist des ganzen Aufrufs (im KI-Auftrag {@code archiv.ki.render-zeitgrenze})
     */
    record Render(int maxSeiten, boolean farbig, long pixelMax, Duration zeitgrenze) {
        public Render {
            if (maxSeiten < 1) {
                throw new IllegalArgumentException("maxSeiten muss mindestens 1 sein");
            }
            if (pixelMax < 1) {
                throw new IllegalArgumentException("pixelMax muss positiv sein");
            }
            Objects.requireNonNull(zeitgrenze, "zeitgrenze");
        }
    }

    /** Verwertet die gerenderten Seiten; läuft vor dem Löschen des Verzeichnisses. */
    @FunctionalInterface
    interface Seitenverwertung<T> {
        T verwerten(List<Path> seiten) throws IOException, InterruptedException;
    }

    /** Verwertet das Ergebnis, z. B. lädt PDF/A und Vorschaubilder hoch; läuft vor dem Löschen des Verzeichnisses. */
    @FunctionalInterface
    interface Verwertung<T> {
        T verwerten(OcrErgebnis ergebnis) throws IOException, InterruptedException;
    }
}
