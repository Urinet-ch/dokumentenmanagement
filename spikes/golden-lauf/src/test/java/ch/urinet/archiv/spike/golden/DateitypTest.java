package ch.urinet.archiv.spike.golden;

import ch.urinet.archiv.spike.golden.GoldenPipeline.Eingabe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Bildweg nach Inhalt, nie nach Endung oder Kategorie der Erwartung (Req 3.1, 3.3). */
class DateitypTest {

    @TempDir
    Path tmp;

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0x0D};
    static final byte[] HEIC = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0};
    static final byte[] HEIF = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'i', 'f', '1', 0, 0, 0, 0};
    static final byte[] TIFF_II = {'I', 'I', '*', 0, 8, 0, 0, 0};
    static final byte[] TIFF_MM = {'M', 'M', 0, '*', 0, 0, 0, 8};
    static final byte[] PDF = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);

    private Path datei(String name, byte[] inhalt) throws IOException {
        Path p = tmp.resolve(name);
        Files.write(p, inhalt);
        return p;
    }

    private Erwartung erwartung(Path datei, String kategorie) {
        return new Erwartung("x", datei, false, "abstimmen", kategorie, null, null, null, null, null, List.of());
    }

    @Test
    void bildtypenNachInhaltAuchBeiIrrefuehrenderEndung() throws IOException {
        assertThat(Dateityp.erkenne(datei("a.pdf", JPEG))).isEqualTo(Dateityp.JPEG);
        assertThat(Dateityp.erkenne(datei("b.txt", PNG))).isEqualTo(Dateityp.PNG);
        assertThat(Dateityp.erkenne(datei("c.jpg", HEIC))).isEqualTo(Dateityp.HEIF);
        assertThat(Dateityp.erkenne(datei("d", HEIF))).isEqualTo(Dateityp.HEIF);
        assertThat(Dateityp.erkenne(datei("e.pdf", TIFF_II))).isEqualTo(Dateityp.TIFF);
        assertThat(Dateityp.erkenne(datei("f.png", TIFF_MM))).isEqualTo(Dateityp.TIFF);
        assertThat(Dateityp.erkenne(datei("g.jpg", PDF))).isEqualTo(Dateityp.PDF);
        assertThat(Dateityp.erkenne(datei("h.jpg", new byte[]{1, 2}))).isEqualTo(Dateityp.UNBEKANNT);
        assertThat(Dateityp.erkenne(datei("leer.jpg", new byte[0]))).isEqualTo(Dateityp.UNBEKANNT);
    }

    @Test
    void wegNachInhaltUnabhaengigVonKategorie() throws IOException {
        assertThat(GoldenPipeline.eingabe(erwartung(datei("foto.pdf", JPEG), "QUITTUNG"), false)).isEqualTo(Eingabe.BILD);
        assertThat(GoldenPipeline.eingabe(erwartung(datei("scan.heic", PDF), "HANDY_FOTO"), false)).isEqualTo(Eingabe.TEXT);
        assertThat(GoldenPipeline.eingabe(erwartung(datei("tiff.dat", TIFF_II), "LIEFERANTENRECHNUNG"), false))
                .isEqualTo(Eingabe.BILD);
        assertThat(GoldenPipeline.eingabe(erwartung(datei("text.pdf", PDF), "QUITTUNG"), true)).isEqualTo(Eingabe.BILD);
    }
}
