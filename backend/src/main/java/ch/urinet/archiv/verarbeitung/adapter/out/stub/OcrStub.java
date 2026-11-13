package ch.urinet.archiv.verarbeitung.adapter.out.stub;

import ch.urinet.archiv.verarbeitung.application.OcrPort;
import ch.urinet.archiv.verarbeitung.domain.OcrEingabe;
import ch.urinet.archiv.verarbeitung.domain.OcrErgebnis;
import ch.urinet.archiv.verarbeitung.domain.OcrFehlgeschlagen;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dev-Stub von {@link OcrPort}: ohne OCRmyPDF; aktiv mit {@code archiv.ocr.stub=true} (Profil dev). Liefert als
 * «PDF/A» ein leeres einseitiges PDF (kein echtes PDF/A), keinen Volltext und keine Vorschaubilder.
 */
@ApplicationScoped
@Typed(OcrStub.class)
public class OcrStub implements OcrPort {

    @Override
    public <T> T verarbeiten(OcrEingabe eingabe, Verwertung<T> verwertung) throws InterruptedException {
        Path lauf = null;
        try {
            lauf = Files.createTempDirectory("archiv-ocr-stub-");
            Path pdf = Files.write(lauf.resolve("pdfa.pdf"), leeresPdf());
            return verwertung.verwerten(new OcrErgebnis(Optional.of(pdf), Optional.empty(), "", List.of(), false,
                    false));
        } catch (IOException e) {
            throw new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.EIN_AUSGABE, "Dev-Stub: Datei nicht schreibbar", e);
        } finally {
            if (lauf != null) {
                try {
                    Files.deleteIfExists(lauf.resolve("pdfa.pdf"));
                    Files.deleteIfExists(lauf);
                } catch (IOException e) {
                    // nur Entwicklung; das Temp-Verzeichnis räumt das System auf
                }
            }
        }
    }

    /** Ohne pdftotext kein Text: der KI-Vorschlag nimmt dann den Volltext (im Profil dev leer). */
    @Override
    public String layoutText(Path pdf, Duration zeitgrenze) {
        return "";
    }

    /** Ohne pdftoppm keine Seiten: kein Swiss QR Code und kein Bildweg im Profil dev. */
    @Override
    public <T> T seitenbilder(Path pdf, Render render, Seitenverwertung<T> verwertung) throws InterruptedException {
        try {
            return verwertung.verwerten(List.of());
        } catch (IOException e) {
            throw new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.EIN_AUSGABE, "Dev-Stub: Seiten nicht verwertbar", e);
        }
    }

    /** Kleinstes gültiges PDF mit einer leeren A4-Seite; Verweistabelle mit berechneten Byte-Positionen. */
    static byte[] leeresPdf() {
        List<String> objekte = List.of("<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << >> >>");
        var aus = new ByteArrayOutputStream();
        List<Integer> positionen = new ArrayList<>();
        schreiben(aus, "%PDF-1.4\n");
        for (int i = 0; i < objekte.size(); i++) {
            positionen.add(aus.size());
            schreiben(aus, (i + 1) + " 0 obj\n" + objekte.get(i) + "\nendobj\n");
        }
        int xref = aus.size();
        var tabelle = new StringBuilder("xref\n0 " + (objekte.size() + 1) + "\n0000000000 65535 f \n");
        positionen.forEach(p -> tabelle.append(String.format("%010d 00000 n \n", p)));
        schreiben(aus, tabelle + "trailer\n<< /Size " + (objekte.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref
                + "\n%%EOF\n");
        return aus.toByteArray();
    }

    private static void schreiben(ByteArrayOutputStream aus, String text) {
        aus.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }
}
