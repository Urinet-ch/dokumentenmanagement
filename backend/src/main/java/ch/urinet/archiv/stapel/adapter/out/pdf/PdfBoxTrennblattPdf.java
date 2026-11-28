package ch.urinet.archiv.stapel.adapter.out.pdf;

import ch.urinet.archiv.stapel.application.TrennblattPdfPort;
import ch.urinet.archiv.stapel.domain.TrennblattCode;
import ch.urinet.archiv.stapel.domain.TrennblattSatz;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Trennblätter als PDF (Spec 09, Req 6.2, 6.3; design.md «Trennblatt-Generator»): A4 hochkant, je Blatt zwei gleiche
 * Seiten, oben «TRENNBLATT» bzw. «ORDNER», darunter Art bzw. Titel in 36 pt (bei langen Titeln umbrochen und
 * höchstens bis 24 pt verkleinert), mittig der QR-Code mit 60 mm Kantenlänge (ZXing, Fehlerkorrektur M, Ruhezone
 * mindestens 4 Module weiss), unten der Hinweis zum Druck. Der QR-Code ist als Vektor gezeichnet; alle Seiten eines
 * Satzes zeichnen dieselbe Form (XObject), das PDF bleibt so auch bei 100 Blättern klein.
 * <p>
 * Schrift: Liberation Sans aus dem PDFBox-Artefakt (SIL Open Font License), als Teilmenge eingebettet
 * ({@link PDType0Font}), damit Umlaute und Guillemets ohne Schriften des Systems sicher sind. Einen Titel mit Zeichen
 * ohne Glyphe (z. B. CJK, Emoji) lehnt {@code TrennblattService} vorher über {@link #darstellbar(String)} ab. Nur der
 * Name einer Art, den der Inhaber pflegt, kann solche Zeichen noch enthalten; sie erscheinen als «?», darunter steht
 * der Code der Art in ASCII.
 */
@ApplicationScoped
public class PdfBoxTrennblattPdf implements TrennblattPdfPort {

    static final String SCHRIFT = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
    static final float MM = 72f / 25.4f;
    static final float QR_MM = 60f;
    static final int RUHEZONE_MODULE = 4;
    static final String HINWEIS = "Beidseitig auf farbiges Papier drucken. Wiederverwendbar.";
    static final String OHNE_ART = "Ohne Art";

    private static final PDRectangle A4 = PDRectangle.A4;
    private static final float RAND = 20 * MM;
    private static final float[] GROESSEN = {36f, 30f, 24f};
    private static final int ZEILEN_MAX = 3;
    private static final Map<EncodeHintType, Object> QR_HINWEISE = Map.of(
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN, 0);

    @Override
    public byte[] erzeugen(List<TrennblattSatz> saetze) {
        try (PDDocument pdf = new PDDocument()) {
            PDFont schrift = schrift(pdf);
            for (TrennblattSatz satz : saetze) {
                PDFormXObject qr = qr(pdf, satz.code().inhalt());
                for (int i = 0; i < satz.anzahl() * 2; i++) {
                    PDPage blatt = new PDPage(A4);
                    pdf.addPage(blatt);
                    try (PDPageContentStream inhalt = new PDPageContentStream(pdf, blatt)) {
                        texte(inhalt, schrift, satz);
                        inhalt.drawForm(qr);
                    }
                }
            }
            pdf.getDocumentInformation().setTitle("Trennblätter");
            ByteArrayOutputStream aus = new ByteArrayOutputStream();
            pdf.save(aus);
            return aus.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean darstellbar(String text) {
        return text.codePoints().allMatch(c -> Abdeckung.CMAP.getGlyphId(c) > 0);
    }

    /** Unicode-Tabelle der Schrift, einmal gelesen (nur lesend genutzt). */
    private static final class Abdeckung {
        static final CmapLookup CMAP = laden();

        private static CmapLookup laden() {
            try (InputStream ein = PDDocument.class.getResourceAsStream(SCHRIFT)) {
                if (ein == null) {
                    throw new IllegalStateException("Schrift für Trennblätter fehlt im PDFBox-Artefakt");
                }
                return new TTFParser().parse(new RandomAccessReadBuffer(ein)).getUnicodeCmapLookup();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static PDFont schrift(PDDocument pdf) throws IOException {
        try (InputStream ein = PDDocument.class.getResourceAsStream(SCHRIFT)) {
            if (ein == null) {
                throw new IllegalStateException("Schrift für Trennblätter fehlt im PDFBox-Artefakt");
            }
            return PDType0Font.load(pdf, ein, true);
        }
    }

    /** Überschrift, Aufschrift, Code der Art und Hinweis; direkt auf der Seite, damit PDFBox die Teilmenge der Schrift kennt. */
    private static void texte(PDPageContentStream inhalt, PDFont schrift, TrennblattSatz satz) throws IOException {
        TrennblattCode code = satz.code();
        float y = A4.getHeight() - RAND - 22;
        zentriert(inhalt, schrift, 22, code.typ() == TrennblattCode.Typ.TRENN ? "TRENNBLATT" : "ORDNER", y);
        String aufschrift = darstellbar(schrift, satz.aufschrift() == null ? OHNE_ART : satz.aufschrift());
        float groesse = GROESSEN[GROESSEN.length - 1];
        List<String> zeilen = List.of();
        for (float g : GROESSEN) {
            groesse = g;
            zeilen = umbrechen(schrift, groesse, aufschrift, A4.getWidth() - 2 * RAND);
            if (zeilen.size() <= ZEILEN_MAX) {
                break;
            }
        }
        y -= 14 * MM;
        for (String zeile : zeilen) {
            zentriert(inhalt, schrift, groesse, zeile, y);
            y -= groesse * 1.2f;
        }
        if (code.art() != null) {
            zentriert(inhalt, schrift, 14, "Art " + code.art(), y - 2 * MM);
        }
        zentriert(inhalt, schrift, 12, HINWEIS, RAND);
    }

    /**
     * QR-Code als Form mit Rechtecken je Lauf dunkler Module, mittig, Mitte 15 mm unter der Seitenmitte; jede Seite
     * eines Satzes zeichnet dieselbe Form.
     */
    private static PDFormXObject qr(PDDocument pdf, String text) throws IOException {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, QR_HINWEISE);
        } catch (WriterException e) {
            throw new IllegalStateException("QR-Code nicht erzeugbar", e);
        }
        PDFormXObject form = new PDFormXObject(pdf);
        form.setBBox(A4);
        form.setResources(new PDResources());
        float kante = QR_MM * MM;
        float modul = kante / matrix.getWidth();
        float links = (A4.getWidth() - kante) / 2;
        float oben = A4.getHeight() / 2 - 15 * MM + kante / 2;
        try (PDFormContentStream inhalt = new PDFormContentStream(form)) {
            inhalt.setNonStrokingColor(0f);
            for (int zeile = 0; zeile < matrix.getHeight(); zeile++) {
                int spalte = 0;
                while (spalte < matrix.getWidth()) {
                    if (!matrix.get(spalte, zeile)) {
                        spalte++;
                        continue;
                    }
                    int start = spalte;
                    while (spalte < matrix.getWidth() && matrix.get(spalte, zeile)) {
                        spalte++;
                    }
                    inhalt.addRect(links + start * modul, oben - (zeile + 1) * modul, (spalte - start) * modul, modul);
                }
            }
            inhalt.fill();
        }
        return form;
    }

    private static void zentriert(PDPageContentStream inhalt, PDFont schrift, float groesse, String text, float y)
            throws IOException {
        float breite = schrift.getStringWidth(text) / 1000 * groesse;
        inhalt.beginText();
        inhalt.setFont(schrift, groesse);
        inhalt.newLineAtOffset((A4.getWidth() - breite) / 2, y);
        inhalt.showText(text);
        inhalt.endText();
    }

    /** Ersetzt Zeichen ohne Glyphe in der Schrift durch «?». */
    static String darstellbar(PDFont schrift, String text) {
        StringBuilder ergebnis = new StringBuilder();
        text.codePoints().forEach(c -> {
            String zeichen = Character.toString(c);
            try {
                schrift.encode(zeichen);
                ergebnis.append(zeichen);
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                ergebnis.append('?');
            }
        });
        return ergebnis.toString();
    }

    /** Zeilen nach Wörtern; ein Wort breiter als die Zeile wird nach Zeichen getrennt. */
    static List<String> umbrechen(PDFont schrift, float groesse, String text, float breite) throws IOException {
        List<String> zeilen = new ArrayList<>();
        StringBuilder zeile = new StringBuilder();
        for (String wort : text.split(" +")) {
            String kandidat = zeile.isEmpty() ? wort : zeile + " " + wort;
            if (breite(schrift, groesse, kandidat) <= breite) {
                zeile.setLength(0);
                zeile.append(kandidat);
                continue;
            }
            if (!zeile.isEmpty()) {
                zeilen.add(zeile.toString());
                zeile.setLength(0);
            }
            for (int i = 0; i < wort.length(); ) {
                int c = wort.codePointAt(i);
                i += Character.charCount(c);
                if (!zeile.isEmpty() && breite(schrift, groesse, zeile + Character.toString(c)) > breite) {
                    zeilen.add(zeile.toString());
                    zeile.setLength(0);
                }
                zeile.appendCodePoint(c);
            }
        }
        if (!zeile.isEmpty()) {
            zeilen.add(zeile.toString());
        }
        return zeilen;
    }

    private static float breite(PDFont schrift, float groesse, String text) throws IOException {
        return schrift.getStringWidth(text) / 1000 * groesse;
    }
}
