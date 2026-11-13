package ch.urinet.archiv.ki.adapter.out;

import ch.urinet.archiv.ki.adapter.out.bild.AwtSeitenbild;
import ch.urinet.archiv.ki.adapter.out.qr.ZxingQrLeser;
import ch.urinet.archiv.ki.application.SeiteUngeeignet;
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

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Speicherbudget Stufe A (Review PR #93, M3; design.md «Umsetzung 3.2»): Swiss QR Code und Seitenbild aus Seiten in der
 * grössten zulässigen Grösse ({@code archiv.ki.render-pixel-max}) ohne {@link OutOfMemoryError}; Seiten darüber werden
 * ausgelassen, bevor ein Raster angelegt wird (Review PR #120). Läuft nur in der Surefire-Ausführung {@code speicher}
 * mit {@code -Xmx64m} (pom.xml); synthetische Seiten.
 */
@EnabledIfSystemProperty(named = "archiv.speicher-test", matches = "true")
class SeitenKnapperHeapTest {

    /** A4 mit 300 dpi wie {@code pdftoppm -r 300}. */
    private static final int BREITE = 2480;
    private static final int HOEHE = 3508;
    private static final long PIXEL_MAX = 8_750_000;

    @Test
    void heapIstKnapp() {
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(64L * 1024 * 1024);
    }

    @Test
    void qrAusGrauenSeitenMitVollerAufloesung(@TempDir Path ordner) throws Exception {
        String spc = String.join("\n", "SPC", "0200", "1", "CH4431999123000889012", "S", "Lieferant GmbH",
                "Teststrasse", "1", "6000", "Luzern", "CH", "", "", "", "", "", "", "", "99.95", "CHF", "S",
                "Testfirma AG", "Testweg", "2", "6460", "Altdorf", "CH", "NON", "", "Rechnung", "EPD");
        List<Path> seiten = List.of(seite(ordner, "s1.tif", BufferedImage.TYPE_BYTE_GRAY, null),
                seite(ordner, "s2.tif", BufferedImage.TYPE_BYTE_GRAY, null),
                seite(ordner, "s3.tif", BufferedImage.TYPE_BYTE_GRAY, spc));

        Optional<QrDaten> qr = new ZxingQrLeser(PIXEL_MAX).lesen(UUID.randomUUID(), seiten);

        assertThat(qr).hasValueSatisfying(q -> assertThat(q.iban()).isEqualTo("CH4431999123000889012"));
    }

    /**
     * Review PR #120: Eine überformatige graue Seite (rund 0.4 MB auf der Platte, 400 Mio. Pixel) wird ausgelassen,
     * bevor ein Raster angelegt wird; der Code der nächsten Seite wird trotzdem gefunden, ohne {@link OutOfMemoryError}.
     */
    @Test
    void qrUeberformatigeSeiteWirdOhneOomAusgelassen(@TempDir Path ordner) throws Exception {
        String spc = String.join("\n", "SPC", "0200", "1", "CH4431999123000889012", "S", "Lieferant GmbH",
                "Teststrasse", "1", "6000", "Luzern", "CH", "", "", "", "", "", "", "", "99.95", "CHF", "S",
                "Testfirma AG", "Testweg", "2", "6460", "Altdorf", "CH", "NON", "", "Rechnung", "EPD");
        List<Path> seiten = List.of(riesigesTiff(ordner), seite(ordner, "s2.tif", BufferedImage.TYPE_BYTE_GRAY, spc));

        Optional<QrDaten> qr = new ZxingQrLeser(PIXEL_MAX).lesen(UUID.randomUUID(), seiten);

        assertThat(qr).hasValueSatisfying(q -> assertThat(q.iban()).isEqualTo("CH4431999123000889012"));
    }

    /**
     * Review PR #120: dieselbe Seite auf dem Bildweg ergibt {@link SeiteUngeeignet}, keinen OOM; die Meldung belegt,
     * dass ImageIO die Abmessungen aus dem Kopf gelesen hat (die Seite also nicht nur unlesbar ist).
     */
    @Test
    void seitenbildUeberformatigeSeiteOhneOom(@TempDir Path ordner) throws Exception {
        Path riesig = riesigesTiff(ordner);

        assertThatThrownBy(() -> new AwtSeitenbild(PIXEL_MAX).jpeg(List.of(riesig)))
                .isInstanceOf(SeiteUngeeignet.class).hasMessageContaining("400000000 Pixeln über der Grenze");
    }

    @Test
    void seitenbildAusFarbigenSeitenMitVollerAufloesung(@TempDir Path ordner) throws Exception {
        List<Path> seiten = List.of(seite(ordner, "s1.png", BufferedImage.TYPE_3BYTE_BGR, null),
                seite(ordner, "s2.png", BufferedImage.TYPE_3BYTE_BGR, null));

        byte[] jpeg = new AwtSeitenbild(PIXEL_MAX).jpeg(seiten);

        assertThat(jpeg).isNotEmpty();
    }

    /** Bildweg wie im Betrieb (Review PR #93): drei farbige A4-Seiten als PPM aus {@code pdftoppm} ohne {@code -png}. */
    @Test
    void seitenbildAusDreiFarbigenPpmSeitenMitVollerAufloesung(@TempDir Path ordner) throws Exception {
        List<Path> seiten = List.of(ppm(ordner, "s1.ppm", 1), ppm(ordner, "s2.ppm", 2), ppm(ordner, "s3.ppm", 3));

        byte[] jpeg = new AwtSeitenbild(PIXEL_MAX).jpeg(seiten);

        assertThat(jpeg).isNotEmpty();
    }

    /** PPM zeilenweise geschrieben, ohne die ganze Seite im Heap; A4 mit 300 dpi wie bei {@code pdftoppm -r 300}. */
    private static Path ppm(Path ordner, String name, int nummer) throws Exception {
        Path datei = ordner.resolve(name);
        int breite = BREITE + 1;
        try (OutputStream aus = new BufferedOutputStream(Files.newOutputStream(datei))) {
            aus.write(("P6\n" + breite + " " + HOEHE + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            byte[] zeile = new byte[breite * 3];
            for (int y = 0; y < HOEHE; y++) {
                for (int x = 0; x < breite; x++) {
                    boolean streifen = y % 60 < 20 && x >= 200 && x < 2000;
                    zeile[3 * x] = (byte) (streifen ? 0x20 * nummer : 255 - x % 64);
                    zeile[3 * x + 1] = (byte) (streifen ? 0x40 : 255 - y % 32);
                    zeile[3 * x + 2] = (byte) (streifen ? 0x80 : 250);
                }
                aus.write(zeile);
            }
        }
        return datei;
    }

    /**
     * Graues TIFF mit 20'000 × 20'000 weissen Pixeln, mit Deflate komprimiert: rund 0.4 MB auf der Platte, als Raster
     * 400 MB. Zeilenweise geschrieben, ohne die Seite im Heap.
     */
    private static Path riesigesTiff(Path ordner) throws Exception {
        int seite = 20_000;
        ByteArrayOutputStream daten = new ByteArrayOutputStream();
        try (DeflaterOutputStream aus = new DeflaterOutputStream(daten, new Deflater(Deflater.BEST_COMPRESSION))) {
            byte[] zeile = new byte[seite];
            Arrays.fill(zeile, (byte) 255);
            for (int y = 0; y < seite; y++) {
                aus.write(zeile);
            }
        }
        short[][] eintraege = {{256, 4}, {257, 4}, {258, 3}, {259, 3}, {262, 3}, {273, 4}, {277, 3}, {278, 4},
                {279, 4}};
        int ifd = 8;
        int start = ifd + 2 + eintraege.length * 12 + 4;
        long[] werte = {seite, seite, 8, 8, 1, start, 1, seite, daten.size()};
        ByteBuffer kopf = ByteBuffer.allocate(start).order(ByteOrder.LITTLE_ENDIAN);
        kopf.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd);
        kopf.putShort((short) eintraege.length);
        for (int i = 0; i < eintraege.length; i++) {
            kopf.putShort(eintraege[i][0]).putShort(eintraege[i][1]).putInt(1);
            if (eintraege[i][1] == 3) {
                kopf.putShort((short) werte[i]).putShort((short) 0);
            } else {
                kopf.putInt((int) werte[i]);
            }
        }
        kopf.putInt(0);
        Path datei = ordner.resolve("riesig.tif");
        try (OutputStream aus = Files.newOutputStream(datei)) {
            aus.write(kopf.array());
            daten.writeTo(aus);
        }
        return datei;
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
        if (name.endsWith(".png")) {
            ImageIO.write(bild, "png", datei.toFile());
            return datei;
        }
        // graue Seiten wie pdftoppm -gray -tiff -tiffcompression lzw (OcrMyPdfAdapter)
        ImageWriter schreiber = ImageIO.getImageWritersByFormatName("tiff").next();
        ImageWriteParam parameter = schreiber.getDefaultWriteParam();
        parameter.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        parameter.setCompressionType("LZW");
        try (ImageOutputStream aus = ImageIO.createImageOutputStream(datei.toFile())) {
            schreiber.setOutput(aus);
            schreiber.write(null, new IIOImage(bild, null, null), parameter);
        } finally {
            schreiber.dispose();
        }
        return datei;
    }
}
