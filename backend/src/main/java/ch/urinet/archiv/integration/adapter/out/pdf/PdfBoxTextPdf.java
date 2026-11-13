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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        Breiten breitenNormal = new Breiten(normal);
        Breiten breitenFett = new Breiten(fett);
        List<Zeile> zeilen = new ArrayList<>();
        for (String k : kopf) {
            umbrechen(darstellbar(fett, k), breitenFett, breite).forEach(z -> zeilen.add(new Zeile(z, true)));
        }
        if (!kopf.isEmpty()) {
            zeilen.add(new Zeile("", false));
        }
        String inhalt = text.length() > MAX_ZEICHEN ? text.substring(0, MAX_ZEICHEN) + "\n[…]" : text;
        for (String absatz : inhalt.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            umbrechen(darstellbar(normal, absatz), breitenNormal, breite)
                    .forEach(z -> zeilen.add(new Zeile(z, false)));
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

    /**
     * Umbruch an Leerzeichen; ein Wort breiter als die Zeile wird hart getrennt. Die Breite wächst Zeichen für Zeichen
     * mit, darum linear in der Länge des Absatzes, auch bei einem Wort aus 100'000 Zeichen.
     */
    static List<String> umbrechen(String absatz, Breiten breiten, float breite) {
        List<String> zeilen = new ArrayList<>();
        StringBuilder zeile = new StringBuilder();
        float belegt = 0;
        float leer = breiten.von(' ');
        for (String wort : absatz.split(" ", -1)) {
            float weite = breiten.von(wort);
            if (!zeile.isEmpty()) {
                if (belegt + leer + weite <= breite) {
                    zeile.append(' ').append(wort);
                    belegt += leer + weite;
                    continue;
                }
                zeilen.add(zeile.toString());
                zeile.setLength(0);
                belegt = 0;
            }
            if (weite <= breite) {
                zeile.append(wort);
                belegt = weite;
                continue;
            }
            for (int i = 0; i < wort.length(); ) {
                int c = wort.codePointAt(i);
                i += Character.charCount(c);
                float w = breiten.von(c);
                if (!zeile.isEmpty() && belegt + w > breite) {
                    zeilen.add(zeile.toString());
                    zeile.setLength(0);
                    belegt = 0;
                }
                zeile.appendCodePoint(c);
                belegt += w;
            }
        }
        zeilen.add(zeile.toString());
        return zeilen;
    }

    /** Breite je Zeichen einer Schrift in Punkt, zwischengespeichert. */
    static final class Breiten {
        private final PDType1Font schrift;
        private final Map<Integer, Float> je = new HashMap<>();

        Breiten(PDType1Font schrift) {
            this.schrift = schrift;
        }

        float von(String text) {
            float summe = 0;
            for (int i = 0; i < text.length(); ) {
                int c = text.codePointAt(i);
                i += Character.charCount(c);
                summe += von(c);
            }
            return summe;
        }

        float von(int zeichen) {
            return je.computeIfAbsent(zeichen, c -> {
                try {
                    return schrift.getStringWidth(Character.toString(c)) / 1000 * GROESSE;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private record Zeile(String text, boolean fett) {
    }
}
