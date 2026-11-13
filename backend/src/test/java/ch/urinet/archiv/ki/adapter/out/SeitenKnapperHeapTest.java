package ch.urinet.archiv.ki.adapter.out;

import ch.urinet.archiv.ki.adapter.out.bild.AwtSeitenbild;
import ch.urinet.archiv.ki.adapter.out.qr.ZxingQrLeser;
import ch.urinet.archiv.ki.domain.QrDaten;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Speicherbudget Stufe A (Review PR #93, M3; design.md «Umsetzung 3.2»): Swiss QR Code und Seitenbild aus Seiten in der
 * grössten zulässigen Grösse ({@code archiv.ki.render-pixel-max}) ohne {@link OutOfMemoryError}. Läuft nur in der
 * Surefire-Ausführung {@code speicher} mit {@code -Xmx64m} (pom.xml); synthetische Seiten.
 */
@EnabledIfSystemProperty(named = "archiv.speicher-test", matches = "true")
class SeitenKnapperHeapTest {

    /** A4 mit 300 dpi wie {@code pdftoppm -r 300}. */
    private static final int BREITE = 2480;
    private static final int HOEHE = 3508;

    @Test
    void heapIstKnapp() {
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(64L * 1024 * 1024);
    }

    @Test
    void qrAusGrauenSeitenMitVollerAufloesung(@TempDir Path ordner) throws Exception {
        String spc = String.join("\n", "SPC", "0200", "1", "CH4431999123000889012", "S", "Lieferant GmbH",
                "Teststrasse", "1", "6000", "Luzern", "CH", "", "", "", "", "", "", "", "99.95", "CHF", "S",
                "Testfirma AG", "Testweg", "2", "6460", "Altdorf", "CH", "NON", "", "Rechnung", "EPD");
        List<Path> seiten = List.of(seite(ordner, "s1.png", BufferedImage.TYPE_BYTE_GRAY, null),
                seite(ordner, "s2.png", BufferedImage.TYPE_BYTE_GRAY, null),
                seite(ordner, "s3.png", BufferedImage.TYPE_BYTE_GRAY, spc));

        Optional<QrDaten> qr = new ZxingQrLeser().lesen(seiten);

        assertThat(qr).hasValueSatisfying(q -> assertThat(q.iban()).isEqualTo("CH4431999123000889012"));
    }

    @Test
    void seitenbildAusFarbigenSeitenMitVollerAufloesung(@TempDir Path ordner) throws Exception {
        List<Path> seiten = List.of(seite(ordner, "s1.png", BufferedImage.TYPE_3BYTE_BGR, null),
                seite(ordner, "s2.png", BufferedImage.TYPE_3BYTE_BGR, null));

        byte[] jpeg = new AwtSeitenbild().jpeg(seiten);

        assertThat(jpeg).isNotEmpty();
    }

    private static Path seite(Path ordner, String name, int typ, String payload) throws Exception {
        BufferedImage bild = new BufferedImage(BREITE, HOEHE, typ);
        Graphics2D g = bild.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, BREITE, HOEHE);
        g.setColor(new Color(0x20, 0x40, 0x80));
        for (int y = 200; y < 2400; y += 60) {
            g.fillRect(200, y, 1800, 20);
        }
        if (payload != null) {
            BitMatrix m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 540, 540,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET,
                            "UTF-8"));
            g.drawImage(MatrixToImageWriter.toBufferedImage(m), 800, 2700, null);
        }
        g.dispose();
        Path datei = ordner.resolve(name);
        ImageIO.write(bild, "png", datei.toFile());
        return datei;
    }
}
