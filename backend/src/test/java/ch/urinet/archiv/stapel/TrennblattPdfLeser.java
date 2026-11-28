package ch.urinet.archiv.stapel;

import com.google.zxing.Binarizer;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest ein erzeugtes Trennblatt-PDF wie später die Stapelanalyse (Spec 09, design.md «Stapelverarbeitung»): jede Seite
 * mit 150 dpi grau gerastert, QR-Code mit ZXing gelesen; dazu Kantenlänge des QR-Codes, Fehlerkorrektur, Format, Text
 * mit der Schriftgrösse je Zeile (pt) und eingebettete Schriften. Nur für Tests.
 */
public final class TrennblattPdfLeser {

    static final int DPI = 150;

    /**
     * @param qr        Inhalt des QR-Codes, {@code null} ohne lesbaren Code
     * @param kanteMm   Breite des QR-Codes ohne Ruhezone in mm, aus den dunklen Pixeln zwischen den Suchmustern
     * @param korrektur Stufe der Fehlerkorrektur (L, M, Q, H)
     */
    public record Seite(String qr, double kanteMm, String korrektur, float breitePt, float hoehePt, String text,
                        Map<String, Float> zeilen) {
    }

    private TrennblattPdfLeser() {
    }

    public static List<Seite> seiten(byte[] pdf) throws IOException {
        try (PDDocument dokument = Loader.loadPDF(pdf)) {
            PDFRenderer raster = new PDFRenderer(dokument);
            Map<String, Float> zeilen = new LinkedHashMap<>();
            PDFTextStripper text = new PDFTextStripper() {
                @Override
                protected void writeString(String zeile, List<TextPosition> zeichen) throws IOException {
                    zeilen.merge(zeile.strip(), (float) zeichen.stream().mapToDouble(TextPosition::getFontSizeInPt)
                            .max().orElse(0), Math::max);
                    super.writeString(zeile, zeichen);
                }
            };
            List<Seite> seiten = new ArrayList<>();
            for (int i = 0; i < dokument.getNumberOfPages(); i++) {
                PDPage seite = dokument.getPage(i);
                BufferedImage bild = raster.renderImageWithDPI(i, DPI, ImageType.GRAY);
                text.setStartPage(i + 1);
                text.setEndPage(i + 1);
                zeilen.clear();
                String inhalt = text.getText(dokument);
                Result qr = qr(bild);
                seiten.add(new Seite(qr == null ? null : qr.getText(), qr == null ? 0 : kanteMm(bild, qr),
                        qr == null ? null : (String) qr.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL),
                        seite.getMediaBox().getWidth(), seite.getMediaBox().getHeight(), inhalt, Map.copyOf(zeilen)));
            }
            return seiten;
        }
    }

    /** Namen der Schriften aller Seiten und ihrer Formen, je mit «eingebettet» oder «nicht eingebettet». */
    public static List<String> schriften(byte[] pdf) throws IOException {
        try (PDDocument dokument = Loader.loadPDF(pdf)) {
            List<String> schriften = new ArrayList<>();
            for (PDPage seite : dokument.getPages()) {
                sammeln(seite.getResources(), schriften);
            }
            return schriften;
        }
    }

    private static void sammeln(PDResources ressourcen, List<String> schriften) throws IOException {
        if (ressourcen == null) {
            return;
        }
        for (COSName name : ressourcen.getFontNames()) {
            PDFont schrift = ressourcen.getFont(name);
            schriften.add(schrift.getName() + (schrift.isEmbedded() ? " eingebettet" : " nicht eingebettet"));
        }
        for (COSName name : ressourcen.getXObjectNames()) {
            PDXObject objekt = ressourcen.getXObject(name);
            if (objekt instanceof PDFormXObject form) {
                sammeln(form.getResources(), schriften);
            }
        }
    }

    /**
     * Wie {@code ZxingQrLeser}: mehrere Kandidaten je Seite ({@link QRCodeMultiReader}) mit zwei Binarisierern. Ein
     * einzelner {@code QRCodeReader} verfehlt bei 150 dpi einzelne, sonst einwandfreie Codes (z. B. 20 × «ж»); die
     * Seitenanalyse (28.7) braucht darum ebenfalls diesen Weg.
     */
    private static Result qr(BufferedImage bild) {
        BufferedImageLuminanceSource quelle = new BufferedImageLuminanceSource(bild);
        for (Binarizer binarisierer : List.of(new HybridBinarizer(quelle), new GlobalHistogramBinarizer(quelle))) {
            try {
                for (Result r : new QRCodeMultiReader().decodeMultiple(new BinaryBitmap(binarisierer),
                        Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE))) {
                    if (r.getText().startsWith("URINET-")) {
                        return r;
                    }
                }
            } catch (NotFoundException e) {
                // nächster Binarisierer
            }
        }
        return null;
    }

    /**
     * Auf den Zeilen zwischen dem oberen und dem unteren Suchmuster liegt neben dem QR-Code nichts; die äussersten
     * dunklen Pixel dieser Zeilen sind die Kanten des Codes (die Suchmuster reichen bis an den Rand).
     */
    private static double kanteMm(BufferedImage bild, Result qr) {
        ResultPoint[] punkte = qr.getResultPoints();
        int von = (int) Math.min(punkte[1].getY(), punkte[2].getY());
        int bis = (int) punkte[0].getY();
        int links = bild.getWidth();
        int rechts = -1;
        for (int y = von; y <= bis; y++) {
            for (int x = 0; x < bild.getWidth(); x++) {
                if (bild.getRaster().getSample(x, y, 0) < 128) {
                    links = Math.min(links, x);
                    rechts = Math.max(rechts, x);
                }
            }
        }
        return (rechts - links + 1) * 25.4 / DPI;
    }
}
