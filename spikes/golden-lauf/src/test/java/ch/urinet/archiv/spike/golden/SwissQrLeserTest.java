package ch.urinet.archiv.spike.golden;

import ch.urinet.archiv.spike.golden.SwissQrLeser.QrDaten;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Req 7.11: Swiss QR Code aus einem synthetischen Beleg (Seite mit QR-Code im Zahlteil). */
class SwissQrLeserTest {

    static final String PAYLOAD = String.join("\r\n", "SPC", "0200", "1", "CH4431999123000889012", "S",
            "Muster Hosting AG", "Testweg", "1", "6460", "Altdorf", "CH", "", "", "", "", "", "", "",
            "1543.67", "CHF", "S", "Muster Mandant", "Hauptweg", "2", "6462", "Seedorf", "CH",
            "QRR", "210000000003139471430009017", "Rechnung 1001", "EPD");

    @TempDir
    Path tmp;

    @Test
    void parserLiestFelder() {
        QrDaten q = SwissQrLeser.parse(PAYLOAD).orElseThrow();
        assertThat(q.iban()).isEqualTo("CH4431999123000889012");
        assertThat(q.zahlungsempfaenger()).isEqualTo("Muster Hosting AG");
        assertThat(q.betrag()).isEqualByComparingTo(new BigDecimal("1543.67"));
        assertThat(q.waehrung()).isEqualTo("CHF");
        assertThat(q.referenztyp()).isEqualTo("QRR");
        assertThat(q.referenz()).isEqualTo("210000000003139471430009017");
    }

    @Test
    void ohneBetragUndFremderQrCode() {
        String ohneBetrag = PAYLOAD.replace("1543.67", "");
        assertThat(SwissQrLeser.parse(ohneBetrag).orElseThrow().betrag()).isNull();
        assertThat(SwissQrLeser.parse("https://example.org")).isEmpty();
        assertThat(SwissQrLeser.parse(null)).isEmpty();
    }

    @Test
    void liestQrCodeAusGerenderterSeite() throws Exception {
        BufferedImage seite = new BufferedImage(2480, 3508, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = seite.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, seite.getWidth(), seite.getHeight());
        g.setColor(Color.BLACK);
        g.drawString("Rechnung 1001 – Total CHF 1'543.67", 200, 400);
        BufferedImage qr = MatrixToImageWriter.toBufferedImage(new QRCodeWriter().encode(PAYLOAD, BarcodeFormat.QR_CODE,
                543, 543, Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET, "UTF-8")));
        g.drawImage(qr, 800, 2500, null);
        g.dispose();
        Path png = tmp.resolve("seite-1.png");
        ImageIO.write(seite, "png", png.toFile());

        Optional<QrDaten> q = SwissQrLeser.lies(List.of(png));
        assertThat(q).isPresent();
        assertThat(q.get().iban()).isEqualTo("CH4431999123000889012");
        assertThat(q.get().betrag()).isEqualByComparingTo(new BigDecimal("1543.67"));
    }
}
