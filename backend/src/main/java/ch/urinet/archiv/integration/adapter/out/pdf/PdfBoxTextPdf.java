package ch.urinet.archiv.integration.adapter.out.pdf;

import ch.urinet.archiv.integration.application.mail.TextPdfPort;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link TextPdfPort} mit PDFBox (Spec 02, Aufgabe 6.1): A4, Helvetica 10 pt, Kopfzeilen fett, Zeilen am Rand
 * umbrochen. Helvetica kennt nur WinAnsi; jedes andere Zeichen wird «?», damit jede Nachricht ein PDF ergibt. Das PDF ist
 * nur die Quelle der Verarbeitung (OCRmyPDF macht daraus das PDF/A), massgebend bleibt die {@code .eml}. Der Text ist
 * auf {@value #MAX_ZEICHEN} Zeichen begrenzt.
 */
@ApplicationScoped
public class PdfBoxTextPdf implements TextPdfPort {

    static final int MAX_ZEICHEN = 200_000;
    private static final float RAND = 50;
    private static final float GROESSE = 10;
    private static final float ZEILE = 13;

    @Override
    public void erzeugen(List<String> kopf, String text, Path ziel) {
        PDType1Font normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font fett = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        float breite = PDRectangle.A4.getWidth() - 2 * RAND;
        List<Zeile> zeilen = new ArrayList<>();
        for (String k : kopf) {
            umbrechen(darstellbar(fett, k), fett, breite).forEach(z -> zeilen.add(new Zeile(z, true)));
        }
        if (!kopf.isEmpty()) {
            zeilen.add(new Zeile("", false));
        }
        String inhalt = text.length() > MAX_ZEICHEN ? text.substring(0, MAX_ZEICHEN) + "\n[…]" : text;
        for (String absatz : inhalt.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            umbrechen(darstellbar(normal, absatz), normal, breite).forEach(z -> zeilen.add(new Zeile(z, false)));
        }
        try (PDDocument dokument = new PDDocument()) {
            PDPageContentStream seite = null;
            float y = 0;
            try {
                for (Zeile zeile : zeilen) {
                    if (seite == null || y < RAND) {
                        if (seite != null) {
                            seite.endText();
                            seite.close();
                        }
                        PDPage neu = new PDPage(PDRectangle.A4);
                        dokument.addPage(neu);
                        seite = new PDPageContentStream(dokument, neu);
                        seite.beginText();
                        y = PDRectangle.A4.getHeight() - RAND;
                        seite.newLineAtOffset(RAND, y);
                    }
                    seite.setFont(zeile.fett() ? fett : normal, GROESSE);
                    seite.showText(zeile.text());
                    seite.newLineAtOffset(0, -ZEILE);
                    y -= ZEILE;
                }
                if (seite == null) {
                    dokument.addPage(new PDPage(PDRectangle.A4));
                } else {
                    seite.endText();
                }
            } finally {
                if (seite != null) {
                    seite.close();
                }
            }
            dokument.getDocumentInformation().setTitle("E-Mail");
            dokument.save(ziel.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Text-PDF nicht erzeugt", e);
        }
    }

    /** Tabulatoren werden Leerzeichen, Steuerzeichen fallen weg, was die Schrift nicht kennt, wird «?». */
    static String darstellbar(PDType1Font schrift, String text) {
        StringBuilder aus = new StringBuilder();
        text.replace('\t', ' ').codePoints().forEach(c -> {
            if (Character.isISOControl(c)) {
                return;
            }
            String zeichen = Character.toString(c);
            try {
                schrift.encode(zeichen);
                aus.append(zeichen);
            } catch (IOException | IllegalArgumentException e) {
                aus.append('?');
            }
        });
        return aus.toString();
    }

    private static List<String> umbrechen(String absatz, PDType1Font schrift, float breite) {
        List<String> zeilen = new ArrayList<>();
        StringBuilder zeile = new StringBuilder();
        for (String wort : absatz.split(" ", -1)) {
            String versuch = zeile.isEmpty() ? wort : zeile + " " + wort;
            if (zeile.isEmpty() || weite(schrift, versuch) <= breite) {
                zeile.setLength(0);
                zeile.append(versuch);
            } else {
                zeilen.add(zeile.toString());
                zeile.setLength(0);
                zeile.append(wort);
            }
            // ein Wort breiter als die Zeile wird hart getrennt
            while (weite(schrift, zeile.toString()) > breite && zeile.length() > 1) {
                int teil = zeile.length() - 1;
                while (teil > 1 && weite(schrift, zeile.substring(0, teil)) > breite) {
                    teil--;
                }
                zeilen.add(zeile.substring(0, teil));
                zeile.delete(0, teil);
            }
        }
        zeilen.add(zeile.toString());
        return zeilen;
    }

    private static float weite(PDType1Font schrift, String text) {
        try {
            return schrift.getStringWidth(text) / 1000 * GROESSE;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Zeile(String text, boolean fett) {
    }
}
