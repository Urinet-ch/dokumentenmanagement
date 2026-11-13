package ch.urinet.archiv.ki.adapter.out.bild;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Leser für die farbigen Seiten des Bildwegs (@req-7.1, Review PR #93): strenger Kopf, Grenze vor dem Anlegen. */
class PpmLeserTest {

    private static final long PIXEL_MAX = 8_750_000;

    @TempDir
    Path ordner;

    @Test
    void liestRgbAlsDreiByteBgr() throws Exception {
        Path datei = ppm("P6\n2 1\n255\n", new byte[]{10, 20, 30, (byte) 200, (byte) 210, (byte) 220});

        BufferedImage bild = PpmLeser.lesen(datei, PIXEL_MAX);

        assertThat(bild.getType()).isEqualTo(BufferedImage.TYPE_3BYTE_BGR);
        assertThat(bild.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0x0A141E);
        assertThat(bild.getRGB(1, 0) & 0xFFFFFF).isEqualTo(0xC8D2DC);
        assertThat(PpmLeser.kopf(datei, PIXEL_MAX)).isEqualTo(new PpmLeser.Kopf(2, 1));
        assertThat(PpmLeser.istPpm(datei)).isTrue();
    }

    @Test
    void kopfMitBeliebigemLeerraumZwischenDenZahlen() throws Exception {
        Path datei = ppm("P6 \t1\r\n 1  255\n", new byte[]{1, 2, 3});

        assertThat(PpmLeser.kopf(datei, PIXEL_MAX)).isEqualTo(new PpmLeser.Kopf(1, 1));
    }

    @Test
    void ungueltigeKoepfeWerdenAbgelehnt() throws Exception {
        byte[] pixel = {1, 2, 3};
        for (String kopf : List.of("P5\n1 1\n255\n", "P3\n1 1\n255\n", "P6\n1 1\n65535\n", "P6\n1 1\n127\n",
                "P6\n# Kommentar\n1 1\n255\n", "P6\n0 1\n255\n", "P6\n-1 1\n255\n", "P61 1\n255\n", "P6\n1 1\n255",
                "P6\n1 1 255x", "P6\n1234567890 1\n255\n")) {
            Path datei = ppm(kopf, pixel);
            assertThatThrownBy(() -> PpmLeser.lesen(datei, PIXEL_MAX)).as(kopf)
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    void groesseMussGenauZumKopfPassen() throws Exception {
        Path zuKurz = ppm("P6\n2 1\n255\n", new byte[]{1, 2, 3});
        Path zuLang = ppm("P6\n1 1\n255\n", new byte[]{1, 2, 3, 4});

        assertThatThrownBy(() -> PpmLeser.lesen(zuKurz, PIXEL_MAX)).hasMessageContaining("Grösse");
        assertThatThrownBy(() -> PpmLeser.kopf(zuLang, PIXEL_MAX)).hasMessageContaining("Grösse");
    }

    @Test
    void pixelgrenzeGreiftVorDemAnlegen() throws Exception {
        Path datei = ppm("P6\n100000 100000\n255\n", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> PpmLeser.lesen(datei, PIXEL_MAX)).hasMessageContaining("über der Grenze");
        assertThatThrownBy(() -> new AwtSeitenbild(PIXEL_MAX).jpeg(List.of(datei)))
                .hasMessageContaining("über der Grenze");
    }

    private Path ppm(String kopf, byte[] pixel) throws Exception {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        aus.write(kopf.getBytes(StandardCharsets.US_ASCII));
        aus.write(pixel);
        Path datei = Files.createTempFile(ordner, "seite-", ".ppm");
        Files.write(datei, aus.toByteArray());
        return datei;
    }
}
