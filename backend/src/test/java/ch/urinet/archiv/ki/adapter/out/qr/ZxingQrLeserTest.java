package ch.urinet.archiv.ki.adapter.out.qr;

import ch.urinet.archiv.ki.domain.QrDaten;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Req 7.11: Swiss QR Code deterministisch aus den gerenderten Seiten lesen; nur synthetische Zahlteile. */
class ZxingQrLeserTest {

    private static final UUID DOKUMENT = UUID.randomUUID();
    private static final ZxingQrLeser LESER = new ZxingQrLeser(8_750_000);

    /** Swiss Payments Code 2.0 mit 31 Zeilen, Testwerte. */
    static String spc(String betrag, String referenztyp, String referenz) {
        return String.join("\n", "SPC", "0200", "1", "CH4431999123000889012", "S", "Lieferant GmbH", "Teststrasse",
                "1", "6000", "Luzern", "CH", "", "", "", "", "", "", "", betrag, "CHF", "S", "Testfirma AG",
                "Testweg", "2", "6460", "Altdorf", "CH", referenztyp, referenz, "Rechnung", "EPD");
    }

    static Path seite(Path ordner, String name, String payload) throws Exception {
        BufferedImage bild = new BufferedImage(2480, 3508, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bild.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, bild.getWidth(), bild.getHeight());
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

    @Test
    void liestDenCodeAufDerZweitenSeite(@TempDir Path ordner) throws Exception {
        List<Path> seiten = List.of(seite(ordner, "s1.png", null),
                seite(ordner, "s2.png", spc("250.50", "QRR", "210000000003139471430009017")));

        Optional<QrDaten> qr = LESER.lesen(DOKUMENT, seiten);

        assertThat(qr).hasValueSatisfying(q -> {
            assertThat(q.iban()).isEqualTo("CH4431999123000889012");
            assertThat(q.betrag()).isEqualByComparingTo("250.50");
            assertThat(q.waehrung()).isEqualTo("CHF");
            assertThat(q.referenz()).isEqualTo("210000000003139471430009017");
            assertThat(q.zahlungsempfaenger()).isEqualTo("Lieferant GmbH");
        });
    }

    @Test
    void ohneBetragUndReferenz(@TempDir Path ordner) throws Exception {
        Optional<QrDaten> qr = LESER.lesen(DOKUMENT, List.of(seite(ordner, "s.png", spc("", "NON", ""))));

        assertThat(qr).hasValueSatisfying(q -> {
            assertThat(q.betrag()).isNull();
            assertThat(q.referenz()).isNull();
        });
    }

    @Test
    void andereQrCodesZaehlenNicht(@TempDir Path ordner) throws Exception {
        assertThat(LESER.lesen(DOKUMENT, List.of(seite(ordner, "s.png", "https://example.org/beleg")))).isEmpty();
    }

    @Test
    void unlesbareSeiteVerhindertNichts(@TempDir Path ordner) throws Exception {
        Path kaputt = Files.writeString(ordner.resolve("kaputt.png"), "kein Bild");

        assertThat(LESER.lesen(DOKUMENT, List.of(kaputt, seite(ordner, "s.png", spc("1.00", "NON", "")))))
                .isPresent();
    }

    @Test
    void seiteUeberDerPixelgrenzeWirdAusgelassen(@TempDir Path ordner) throws Exception {
        Path seite = seite(ordner, "s.png", spc("1.00", "NON", ""));

        assertThat(new ZxingQrLeser(2480L * 3508 - 1).lesen(DOKUMENT, List.of(seite))).isEmpty();
        assertThat(new ZxingQrLeser(2480L * 3508).lesen(DOKUMENT, List.of(seite))).isPresent();
    }
}
