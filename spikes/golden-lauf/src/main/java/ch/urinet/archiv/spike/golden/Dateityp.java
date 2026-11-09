package ch.urinet.archiv.spike.golden;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

/** Dateityp nach Inhalt (Signatur), nicht nach Endung (Req 3.3); Bildtypen nach Req 3.1. */
public enum Dateityp {
    PDF("pdf"), JPEG("jpg"), PNG("png"), HEIF("heic"), TIFF("tif"), UNBEKANNT("bin");

    /** Marken im ftyp-Kasten von HEIC/HEIF (ISO/IEC 23008-12). */
    private static final Set<String> HEIF_MARKEN = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
            "mif1", "msf1");

    public final String endung;

    Dateityp(String endung) {
        this.endung = endung;
    }

    public boolean istBild() {
        return this == JPEG || this == PNG || this == HEIF || this == TIFF;
    }

    public static Dateityp erkenne(Path datei) throws IOException {
        byte[] k = new byte[16];
        int n;
        try (InputStream in = Files.newInputStream(datei)) {
            n = in.readNBytes(k, 0, k.length);
        }
        return erkenne(Arrays.copyOf(k, n));
    }

    static Dateityp erkenne(byte[] k) {
        if (beginnt(k, '%', 'P', 'D', 'F')) {
            return PDF;
        }
        if (k.length >= 3 && (k[0] & 0xFF) == 0xFF && (k[1] & 0xFF) == 0xD8 && (k[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if (k.length >= 8 && (k[0] & 0xFF) == 0x89 && beginnt(Arrays.copyOfRange(k, 1, 8), 'P', 'N', 'G', '\r', '\n', 0x1A, '\n')) {
            return PNG;
        }
        if (beginnt(k, 'I', 'I', '*', 0) || beginnt(k, 'M', 'M', 0, '*')) {
            return TIFF;
        }
        if (k.length >= 12 && new String(k, 4, 4, StandardCharsets.ISO_8859_1).equals("ftyp")
                && HEIF_MARKEN.contains(new String(k, 8, 4, StandardCharsets.ISO_8859_1))) {
            return HEIF;
        }
        return UNBEKANNT;
    }

    private static boolean beginnt(byte[] k, int... zeichen) {
        if (k.length < zeichen.length) {
            return false;
        }
        for (int i = 0; i < zeichen.length; i++) {
            if ((k[i] & 0xFF) != zeichen[i]) {
                return false;
            }
        }
        return true;
    }
}
