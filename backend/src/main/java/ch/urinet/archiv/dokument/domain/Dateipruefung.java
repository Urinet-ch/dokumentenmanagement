package ch.urinet.archiv.dokument.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Grobe Strukturprüfung beim Upload (Req 3.7): erkennt leere und offensichtlich abgeschnittene Dateien an Anfang und Ende,
 * ohne die Datei zu öffnen oder zu dekodieren. Die tiefe Prüfung (Seiten, OCR, PDF/A) folgt in der Verarbeitung; eine
 * Datei, die dort scheitert, landet in {@code FEHLER} (Req 5).
 * <p>
 * {@code kopf} sind die ersten {@value #KOPF} Bytes, {@code ende} die letzten {@value #ENDE} Bytes (bei kleinen Dateien
 * weniger). Das Ende ist bewusst grosszügig: Handys hängen nach dem Bild Metadaten an (z. B. Samsung nach dem JPEG-Ende),
 * und PDF-Leser suchen {@code %%EOF} ebenfalls nur am Schluss.
 */
public final class Dateipruefung {

    public static final int KOPF = 64;
    public static final int ENDE = 64 * 1024;

    private static final byte[] PDF_KOPF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PDF_ENDE = "%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG_KOPF = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] JPEG_ENDE = {(byte) 0xFF, (byte) 0xD9};
    private static final byte[] PNG_KOPF = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] PNG_ENDE = {'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    private static final byte[] FTYP = "ftyp".getBytes(StandardCharsets.US_ASCII);

    private Dateipruefung() {
    }

    /** {@code true}, wenn die Datei nicht leer ist und Anfang und Ende zum Typ passen. */
    public static boolean lesbar(Dateityp typ, byte[] kopf, byte[] ende, long groesse) {
        Objects.requireNonNull(typ, "typ");
        Objects.requireNonNull(kopf, "kopf");
        Objects.requireNonNull(ende, "ende");
        if (groesse <= 0) {
            return false;
        }
        return switch (typ) {
            case PDF -> beginntMit(kopf, PDF_KOPF) && enthaelt(ende, PDF_ENDE);
            case JPEG -> beginntMit(kopf, JPEG_KOPF) && enthaelt(ende, JPEG_ENDE);
            case PNG -> beginntMit(kopf, PNG_KOPF) && enthaelt(ende, PNG_ENDE);
            case TIFF -> tiff(kopf, groesse);
            case HEIC, HEIF -> ftyp(kopf, groesse);
        };
    }

    /** Kopf {@code II*\0} oder {@code MM\0*}, erstes IFD hinter dem Kopf und innerhalb der Datei. */
    private static boolean tiff(byte[] kopf, long groesse) {
        if (kopf.length < 8) {
            return false;
        }
        boolean intel = kopf[0] == 'I' && kopf[1] == 'I' && kopf[2] == 42 && kopf[3] == 0;
        boolean motorola = kopf[0] == 'M' && kopf[1] == 'M' && kopf[2] == 0 && kopf[3] == 42;
        if (!intel && !motorola) {
            return false;
        }
        long ifd = intel
                ? (kopf[4] & 0xFFL) | (kopf[5] & 0xFFL) << 8 | (kopf[6] & 0xFFL) << 16 | (kopf[7] & 0xFFL) << 24
                : (kopf[7] & 0xFFL) | (kopf[6] & 0xFFL) << 8 | (kopf[5] & 0xFFL) << 16 | (kopf[4] & 0xFFL) << 24;
        // ein IFD hat mindestens Zähler (2), einen Eintrag (12) und den Verweis aufs nächste (4)
        return ifd >= 8 && ifd + 18 <= groesse;
    }

    /** ISO-BMFF: erste Box {@code ftyp} mit plausibler Länge; danach muss noch Inhalt folgen. */
    private static boolean ftyp(byte[] kopf, long groesse) {
        if (kopf.length < 16 || !gleich(kopf, 4, FTYP)) {
            return false;
        }
        long laenge = (kopf[0] & 0xFFL) << 24 | (kopf[1] & 0xFFL) << 16 | (kopf[2] & 0xFFL) << 8 | (kopf[3] & 0xFFL);
        return laenge >= 16 && laenge < groesse;
    }

    private static boolean beginntMit(byte[] daten, byte[] muster) {
        return daten.length >= muster.length && gleich(daten, 0, muster);
    }

    private static boolean enthaelt(byte[] daten, byte[] muster) {
        for (int i = daten.length - muster.length; i >= 0; i--) {
            if (gleich(daten, i, muster)) {
                return true;
            }
        }
        return false;
    }

    private static boolean gleich(byte[] daten, int ab, byte[] muster) {
        if (ab + muster.length > daten.length) {
            return false;
        }
        for (int i = 0; i < muster.length; i++) {
            if (daten[ab + i] != muster[i]) {
                return false;
            }
        }
        return true;
    }
}
