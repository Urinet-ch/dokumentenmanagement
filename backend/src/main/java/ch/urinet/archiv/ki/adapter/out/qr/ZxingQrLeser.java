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
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Liest den Swiss QR Code (Req 7.11, design.md «KI», {@code SwissQrLeser}) aus den mit {@code pdftoppm -r 300}
 * gerenderten Seiten, wie im Golden-Lauf: je Seite das Original und eine Fassung mit halber Grösse, je mit zwei
 * Binarisierern; nur Payloads mit Kopf {@code SPC} zählen. Seite für Seite, damit nur eine Seite im Speicher liegt.
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
        for (BufferedImage variante : List.of(bild, verkleinert(bild, 0.5))) {
            LuminanceSource quelle = new BufferedImageLuminanceSource(variante);
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
        }
        return Optional.empty();
    }

    private static BufferedImage verkleinert(BufferedImage bild, double faktor) {
        int b = Math.max(1, (int) (bild.getWidth() * faktor));
        int h = Math.max(1, (int) (bild.getHeight() * faktor));
        BufferedImage klein = new BufferedImage(b, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = klein.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(bild, 0, 0, b, h, null);
        g.dispose();
        return klein;
    }
}
