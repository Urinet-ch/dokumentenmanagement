package ch.urinet.archiv.ki.adapter.out.bild;

import ch.urinet.archiv.ki.application.SeiteUngeeignet;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Liest farbige Seiten im binären PPM ({@code P6}, Ausgabe von {@code pdftoppm} ohne {@code -png}) direkt in das
 * Byte-Raster eines {@link BufferedImage#TYPE_3BYTE_BGR}, denselben Typ, den ImageIO aus dem PNG des Golden-Laufs
 * liefert (Req 7.1, design.md «Messung Stufe A»): kein Int-Raster und keine Kopie neben dem Bild. Der Kopf wird streng
 * geprüft ({@code P6}, Breite, Höhe, {@code 255}, ohne Kommentare) und gegen {@code pixelMax} sowie die Dateigrösse
 * begrenzt, bevor Speicher angelegt wird; passt etwas nicht, wirft er {@link SeiteUngeeignet} (Review PR #120: der
 * Bildweg geht dann ohne Seitenbild weiter).
 */
public final class PpmLeser {

    private static final int MAX_ZIFFERN = 9;

    private PpmLeser() {
    }

    /** Breite und Höhe aus dem Kopf; wirft {@link SeiteUngeeignet}, wenn Kopf, Grösse oder Pixelzahl nicht stimmen. */
    public static Kopf kopf(Path datei, long pixelMax) throws IOException {
        try (InputStream ein = new BufferedInputStream(Files.newInputStream(datei))) {
            return kopf(ein, Files.size(datei), pixelMax);
        }
    }

    /** Ganze Seite als {@code TYPE_3BYTE_BGR}. */
    public static BufferedImage lesen(Path datei, long pixelMax) throws IOException {
        try (InputStream ein = new BufferedInputStream(Files.newInputStream(datei))) {
            Kopf kopf = kopf(ein, Files.size(datei), pixelMax);
            BufferedImage bild = new BufferedImage(kopf.breite(), kopf.hoehe(), BufferedImage.TYPE_3BYTE_BGR);
            byte[] daten = ((DataBufferByte) bild.getRaster().getDataBuffer()).getData();
            if (ein.readNBytes(daten, 0, daten.length) != daten.length) {
                throw new IOException("PPM zu kurz");
            }
            for (int i = 0; i < daten.length; i += 3) {
                byte rot = daten[i];
                daten[i] = daten[i + 2];
                daten[i + 2] = rot;
            }
            return bild;
        }
    }

    /** Erkennt eine PPM-Datei an {@code P6} am Anfang. */
    public static boolean istPpm(Path datei) throws IOException {
        try (InputStream ein = Files.newInputStream(datei)) {
            byte[] anfang = ein.readNBytes(2);
            return anfang.length == 2 && anfang[0] == 'P' && anfang[1] == '6';
        }
    }

    public record Kopf(int breite, int hoehe) {
    }

    private static Kopf kopf(InputStream ein, long dateigroesse, long pixelMax) throws IOException {
        if (ein.read() != 'P' || ein.read() != '6') {
            throw new SeiteUngeeignet("kein PPM (P6)");
        }
        long kopflaenge = 2;
        int[] zahlen = new int[3];
        int zeichen = ein.read();
        for (int i = 0; i < 3; i++) {
            if (!leerzeichen(zeichen)) {
                throw new SeiteUngeeignet("PPM-Kopf ungültig");
            }
            while (leerzeichen(zeichen)) {
                kopflaenge++;
                zeichen = ein.read();
            }
            long wert = 0;
            int ziffern = 0;
            while (zeichen >= '0' && zeichen <= '9') {
                if (++ziffern > MAX_ZIFFERN) {
                    throw new SeiteUngeeignet("PPM-Kopf ungültig");
                }
                wert = wert * 10 + (zeichen - '0');
                kopflaenge++;
                zeichen = ein.read();
            }
            if (ziffern == 0 || wert == 0) {
                throw new SeiteUngeeignet("PPM-Kopf ungültig");
            }
            zahlen[i] = (int) wert;
        }
        if (!leerzeichen(zeichen)) {
            throw new SeiteUngeeignet("PPM-Kopf ungültig");
        }
        kopflaenge++;
        if (zahlen[2] != 255) {
            throw new SeiteUngeeignet("PPM nicht mit 8 Bit je Kanal");
        }
        long pixel = (long) zahlen[0] * zahlen[1];
        if (pixel > pixelMax) {
            throw new SeiteUngeeignet("Seite mit " + pixel + " Pixeln über der Grenze");
        }
        if (kopflaenge + 3 * pixel != dateigroesse) {
            throw new SeiteUngeeignet("PPM-Grösse passt nicht zum Kopf");
        }
        return new Kopf(zahlen[0], zahlen[1]);
    }

    private static boolean leerzeichen(int zeichen) {
        return zeichen == ' ' || zeichen == '\t' || zeichen == '\n' || zeichen == '\r' || zeichen == 0x0b
                || zeichen == '\f';
    }
}
