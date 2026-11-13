package ch.urinet.archiv.ki.adapter.out.qr;

import ch.urinet.archiv.ki.application.QrLeserPort;
import ch.urinet.archiv.ki.domain.QrDaten;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Liest den Swiss QR Code (Req 7.11, design.md «KI», {@code SwissQrLeser}) aus den mit {@code pdftoppm -r 300}
 * gerenderten Seiten, wie im Golden-Lauf: je Seite das Original und, nur wenn dort keiner gefunden wird, eine Fassung
 * mit halber Grösse, je mit zwei Binarisierern; nur Payloads mit Kopf {@code SPC} zählen. Speicher (Stufe A,
 * design.md «Umsetzung 3.2»): Seite für Seite, graue Seiten ({@code pdftoppm -gray}) ohne Kopie als Helligkeit, die
 * halbe Fassung als Byte-Feld; eine Seite von 8.7 Mio. Pixeln belegt so rund 11 MB statt über 40 MB.
 */
@ApplicationScoped
public class ZxingQrLeser implements QrLeserPort {

    private static final Logger LOG = Logger.getLogger(ZxingQrLeser.class);

    private static final Map<DecodeHintType, Object> HINWEISE = Map.of(
            DecodeHintType.TRY_HARDER, Boolean.TRUE,
            DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET, "UTF-8");

    @Override
    public Optional<QrDaten> lesen(List<Path> seiten) {
        for (Path seite : seiten) {
            try {
                BufferedImage bild = ImageIO.read(seite.toFile());
                if (bild == null) {
                    continue;
                }
                Optional<QrDaten> qr = lesen(bild);
                if (qr.isPresent()) {
                    return qr;
                }
            } catch (IOException | RuntimeException e) {
                // eine unlesbare Seite verhindert den Vorschlag nicht; nur der Klassenname (Req 16.7)
                LOG.debugf("Swiss QR Code: Seite nicht lesbar (%s)", e.getClass().getName());
            }
        }
        return Optional.empty();
    }

    static Optional<QrDaten> lesen(BufferedImage bild) {
        int b = bild.getWidth();
        int h = bild.getHeight();
        byte[] grau = luminanz(bild);
        Optional<QrDaten> qr = lesen(new PlanarYUVLuminanceSource(grau, b, h, 0, 0, b, h, false));
        if (qr.isPresent() || b < 2 || h < 2) {
            return qr;
        }
        return lesen(new PlanarYUVLuminanceSource(halbiert(grau, b, h), b / 2, h / 2, 0, 0, b / 2, h / 2, false));
    }

    private static Optional<QrDaten> lesen(LuminanceSource quelle) {
        for (BinaryBitmap bitmap : List.of(new BinaryBitmap(new HybridBinarizer(quelle)),
                new BinaryBitmap(new GlobalHistogramBinarizer(quelle)))) {
            try {
                for (Result r : new GenericMultipleBarcodeReader(new MultiFormatReader())
                        .decodeMultiple(bitmap, HINWEISE)) {
                    Optional<QrDaten> qr = QrDaten.parse(r.getText());
                    if (qr.isPresent()) {
                        return qr;
                    }
                }
            } catch (NotFoundException e) {
                // nächste Variante
            }
        }
        return Optional.empty();
    }

    /**
     * Helligkeit je Pixel (ein Byte): eine graue Seite ({@code pdftoppm -gray}) ohne Kopie direkt aus dem Raster, sonst
     * mit der Formel von ZXing ({@code BufferedImageLuminanceSource}), Zeile für Zeile.
     */
    static byte[] luminanz(BufferedImage bild) {
        int b = bild.getWidth();
        int h = bild.getHeight();
        if (bild.getType() == BufferedImage.TYPE_BYTE_GRAY
                && bild.getRaster().getDataBuffer() instanceof DataBufferByte puffer
                && puffer.getNumBanks() == 1 && puffer.getData().length == b * h) {
            return puffer.getData();
        }
        byte[] grau = new byte[b * h];
        int[] zeile = new int[b];
        for (int y = 0; y < h; y++) {
            bild.getRGB(0, y, b, 1, zeile, 0, b);
            for (int x = 0; x < b; x++) {
                int p = zeile[x];
                int wert = (p >>> 24) == 0 ? 0xFF
                        : (306 * ((p >> 16) & 0xFF) + 601 * ((p >> 8) & 0xFF) + 117 * (p & 0xFF) + 0x200) >> 10;
                grau[y * b + x] = (byte) wert;
            }
        }
        return grau;
    }

    /** Halbe Grösse als Mittel je 2×2 Pixel (Flächenmittel wie {@code SCALE_SMOOTH} im Golden-Lauf). */
    static byte[] halbiert(byte[] grau, int b, int h) {
        int hb = b / 2;
        int hh = h / 2;
        byte[] klein = new byte[hb * hh];
        for (int y = 0; y < hh; y++) {
            int o = 2 * y * b;
            int u = o + b;
            for (int x = 0; x < hb; x++) {
                int summe = (grau[o + 2 * x] & 0xFF) + (grau[o + 2 * x + 1] & 0xFF) + (grau[u + 2 * x] & 0xFF)
                        + (grau[u + 2 * x + 1] & 0xFF);
                klein[y * hb + x] = (byte) ((summe + 2) / 4);
            }
        }
        return klein;
    }
}
