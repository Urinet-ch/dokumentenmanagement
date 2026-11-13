package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import ch.urinet.archiv.ki.adapter.out.bild.AwtSeitenbild;
import ch.urinet.archiv.ki.adapter.out.bild.PpmLeser;
import ch.urinet.archiv.verarbeitung.application.OcrPort.Render;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Leitplanke Gate für den Bildweg (@req-7.1, Review PR #93): Die farbigen Seiten als PPM ergeben dieselben Pixel und
 * dasselbe JPEG wie der PNG-Weg des Golden-Laufs ({@code pdftoppm -r 300 -png}, ImageIO). Gerendert wird mit den
 * echten Werkzeugen im Image der Stufe {@code werkzeuge}; synthetische farbige Seiten.
 */
class SeitenbildGleichheitTest {

    private static final Path BASIS = Path.of("target/ocr-arbeit/gleichheit").toAbsolutePath();
    private static final long PIXEL_MAX = 8_750_000;
    private static OcrEinstellungen einstellungen;

    @BeforeAll
    static void imageBauen() throws Exception {
        einstellungen = new OcrEinstellungen(Duration.ofMinutes(5), 1, BASIS,
                Optional.of(WerkzeugImage.sicherstellen()), "docker", "/usr/bin:/bin");
    }

    @Test
    void eineSeiteGleichWieMitPng() throws Exception {
        vergleichen(1);
    }

    @Test
    void dreiSeitenGleichWieMitPng() throws Exception {
        vergleichen(3);
    }

    private void vergleichen(int anzahl) throws Exception {
        Path lauf = Files.createDirectories(BASIS.resolve(UUID.randomUUID().toString()));
        try {
            vergleichen(anzahl, lauf);
        } finally {
            try (var dateien = Files.walk(lauf)) {
                dateien.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private void vergleichen(int anzahl, Path lauf) throws Exception {
        List<BufferedImage> bilder = new ArrayList<>();
        for (int i = 1; i <= anzahl; i++) {
            bilder.add(farbigeSeite(i));
        }
        Path pdf = Files.write(lauf.resolve("foto.pdf"), OcrTestdateien.bildPdf(bilder));
        List<Path> png = pngWieImGoldenLauf(pdf, lauf, anzahl);
        AwtSeitenbild seitenbild = new AwtSeitenbild(PIXEL_MAX);
        byte[] jpegPng = seitenbild.jpeg(png);

        new OcrMyPdfAdapter(einstellungen).seitenbilder(pdf, new Render(20, true, PIXEL_MAX, Duration.ofMinutes(5)),
                ppm -> {
                    assertThat(ppm).hasSize(anzahl).allSatisfy(p -> assertThat(p.toString()).endsWith(".ppm"));
                    for (int i = 0; i < anzahl; i++) {
                        BufferedImage alt = ImageIO.read(png.get(i).toFile());
                        BufferedImage neu = PpmLeser.lesen(ppm.get(i), PIXEL_MAX);
                        assertThat(alt.getType()).isEqualTo(BufferedImage.TYPE_3BYTE_BGR);
                        assertThat(neu.getType()).isEqualTo(alt.getType());
                        assertThat(neu.getWidth()).isEqualTo(alt.getWidth()).isGreaterThan(2400);
                        assertThat(neu.getHeight()).isEqualTo(alt.getHeight()).isGreaterThan(3400);
                        assertThat(daten(neu)).as("Pixel Seite %d", i + 1).isEqualTo(daten(alt));
                    }
                    assertThat(seitenbild.jpeg(ppm)).as("JPEG").isEqualTo(jpegPng);
                    return null;
                });
    }

    /** Wie bis Review PR #93 im {@code OcrMyPdfAdapter}: je Seite {@code pdftoppm -q -r 300 -f n -l n -png}. */
    private static List<Path> pngWieImGoldenLauf(Path pdf, Path lauf, int anzahl) throws Exception {
        Werkzeugstarter starter = new Werkzeugstarter(einstellungen, Clock.systemUTC());
        List<Path> seiten = new ArrayList<>();
        for (int i = 1; i <= anzahl; i++) {
            Werkzeugstarter.Lauf ergebnis = starter.ausfuehren(Befehl.von("pdftoppm", "-q", "-r", "300", "-f",
                    Integer.toString(i), "-l", Integer.toString(i), "-png", pdf.toString(),
                    lauf.resolve("alt").toString()), lauf, Instant.now().plus(Duration.ofMinutes(5)));
            assertThat(ergebnis.erfolgreich()).isTrue();
        }
        try (var dateien = Files.list(lauf)) {
            dateien.filter(p -> p.getFileName().toString().matches("alt-\\d+\\.png")).sorted().forEach(seiten::add);
        }
        assertThat(seiten).hasSize(anzahl);
        return seiten;
    }

    private static byte[] daten(BufferedImage bild) {
        return ((DataBufferByte) bild.getRaster().getDataBuffer()).getData();
    }

    /** Foto-ähnliche Seite: Verlauf, Rauschen, farbige Flächen und Text. */
    private static BufferedImage farbigeSeite(int nummer) {
        BufferedImage bild = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bild.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(250, 240, 210), 1240, 1754, new Color(90, 140, 200)));
        g.fillRect(0, 0, 1240, 1754);
        Random zufall = new Random(nummer);
        for (int i = 0; i < 40; i++) {
            g.setColor(new Color(zufall.nextInt(256), zufall.nextInt(256), zufall.nextInt(256)));
            g.fillOval(zufall.nextInt(1100), zufall.nextInt(1600), 20 + zufall.nextInt(200), 20 + zufall.nextInt(200));
        }
        g.setColor(new Color(20, 20, 60));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 36));
        for (int z = 0; z < 20; z++) {
            g.drawString("Seite " + nummer + " Testfirma AG Rechnung CHF 1'234.50", 100, 150 + z * 70);
        }
        g.dispose();
        for (int y = 0; y < bild.getHeight(); y += 3) {
            for (int x = 0; x < bild.getWidth(); x += 3) {
                bild.setRGB(x, y, bild.getRGB(x, y) ^ zufall.nextInt(0x101010));
            }
        }
        return bild;
    }
}
