package ch.urinet.archiv.spike.golden;

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

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Req 7.11, design.md «SwissQrLeser»: QR deterministisch lesen, nur Payloads mit Kopf SPC. */
public final class SwissQrLeser {

    public record QrDaten(String iban, BigDecimal betrag, String waehrung, String referenztyp, String referenz,
                          String zahlungsempfaenger) {
    }

    private static final Map<DecodeHintType, Object> HINWEISE = Map.of(
            DecodeHintType.TRY_HARDER, Boolean.TRUE,
            DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET, "UTF-8");

    private SwissQrLeser() {
    }

    /** Erster Swiss QR Code auf den gerenderten Seiten (300 dpi). */
    public static Optional<QrDaten> lies(List<Path> seiten) throws IOException {
        for (Path seite : seiten) {
            BufferedImage bild = ImageIO.read(seite.toFile());
            if (bild == null) {
                continue;
            }
            Optional<QrDaten> qr = lies(bild);
            if (qr.isPresent()) {
                return qr;
            }
        }
        return Optional.empty();
    }

    static Optional<QrDaten> lies(BufferedImage bild) {
        for (BufferedImage variante : List.of(bild, verkleinert(bild, 0.5))) {
            LuminanceSource quelle = new BufferedImageLuminanceSource(variante);
            for (BinaryBitmap bitmap : List.of(new BinaryBitmap(new HybridBinarizer(quelle)),
                    new BinaryBitmap(new GlobalHistogramBinarizer(quelle)))) {
                try {
                    for (Result r : new GenericMultipleBarcodeReader(new MultiFormatReader()).decodeMultiple(bitmap, HINWEISE)) {
                        Optional<QrDaten> qr = parse(r.getText());
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
        int b = (int) (bild.getWidth() * faktor), h = (int) (bild.getHeight() * faktor);
        BufferedImage klein = new BufferedImage(b, h, BufferedImage.TYPE_INT_RGB);
        klein.getGraphics().drawImage(bild.getScaledInstance(b, h, Image.SCALE_SMOOTH), 0, 0, null);
        return klein;
    }

    /** Swiss Payments Code 2.0: Zeile 3 IBAN, 5 Name, 18 Betrag, 19 Währung, 27 Referenztyp, 28 Referenz. */
    public static Optional<QrDaten> parse(String payload) {
        if (payload == null) {
            return Optional.empty();
        }
        String[] z = payload.split("\\r?\\n", -1);
        if (z.length < 31 || !"SPC".equals(z[0].strip())) {
            return Optional.empty();
        }
        String betrag = z[18].strip();
        return Optional.of(new QrDaten(z[3].strip(), betrag.isEmpty() ? null : new BigDecimal(betrag), z[19].strip(),
                z[27].strip(), z[28].strip().isEmpty() ? null : z[28].strip(), z[5].strip()));
    }
}
