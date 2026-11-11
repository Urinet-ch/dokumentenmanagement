package ch.urinet.archiv.dokument;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.zip.CRC32;

/**
 * Synthetische, kleinste gültige Dateien je Typ für den Upload (Req 3): Magic Bytes und Endmarker stimmen, der Inhalt
 * ist zufällig, damit jede Datei eine eigene SHA-256 hat. Keine echten Belege (Req 16.15).
 */
public final class Testdateien {

    private static final SecureRandom ZUFALL = new SecureRandom();

    private Testdateien() {
    }

    /** Datei des Typs ({@code pdf}, {@code jpeg}, {@code png}, {@code heic}, {@code heif}, {@code tiff}, {@code text}). */
    public static byte[] erzeugen(String typ) {
        return switch (typ) {
            case "pdf" -> pdf();
            case "jpeg" -> jpeg();
            case "png" -> png();
            case "heic" -> isoBmff("heic");
            case "heif" -> isoBmff("mif1");
            case "tiff" -> tiff();
            case "text" -> ("Notiz " + zufall() + "\n").getBytes(StandardCharsets.UTF_8);
            case "leer" -> new byte[0];
            case "abgeschnitten" -> abgeschnitten();
            default -> throw new IllegalArgumentException("Unbekannter Testtyp: " + typ);
        };
    }

    public static byte[] pdf() {
        return ("%PDF-1.4\n% Testdokument " + zufall() + "\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n"
                + "%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    /** PDF mit genau {@code groesse} Bytes: Kommentarzeilen füllen auf. */
    public static byte[] pdf(long groesse) {
        byte[] kopf = ("%PDF-1.4\n% Testdokument " + zufall() + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] ende = "\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        byte[] inhalt = new byte[Math.toIntExact(groesse)];
        System.arraycopy(kopf, 0, inhalt, 0, kopf.length);
        java.util.Arrays.fill(inhalt, kopf.length, inhalt.length - ende.length, (byte) ' ');
        inhalt[kopf.length] = '%';
        System.arraycopy(ende, 0, inhalt, inhalt.length - ende.length, ende.length);
        return inhalt;
    }

    /** PDF ohne Endmarker: wie eine abgebrochene Übertragung. */
    public static byte[] abgeschnitten() {
        return ("%PDF-1.4\n% Testdokument " + zufall() + "\n1 0 obj << /Type /Catalog >> endobj\ntrai")
                .getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] jpeg() {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        aus.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00,
                0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00});
        byte[] kommentar = zufall().getBytes(StandardCharsets.US_ASCII);
        aus.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, (byte) (kommentar.length + 2)});
        aus.writeBytes(kommentar);
        aus.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD9});
        return aus.toByteArray();
    }

    public static byte[] png() {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        aus.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(1).putInt(1).put((byte) 8).put((byte) 0).put((byte) 0)
                .put((byte) 0).put((byte) 0);
        chunk(aus, "IHDR", ihdr.array());
        chunk(aus, "tEXt", ("Comment\0" + zufall()).getBytes(StandardCharsets.ISO_8859_1));
        chunk(aus, "IEND", new byte[0]);
        return aus.toByteArray();
    }

    /** HEIC ({@code heic}) oder HEIF ({@code mif1}): Box {@code ftyp}, danach eine freie Box mit Zufall. */
    public static byte[] isoBmff(String marke) {
        ByteBuffer ftyp = ByteBuffer.allocate(24).putInt(24).put("ftyp".getBytes(StandardCharsets.US_ASCII))
                .put(marke.getBytes(StandardCharsets.US_ASCII)).putInt(0)
                .put("mif1".getBytes(StandardCharsets.US_ASCII)).put(marke.getBytes(StandardCharsets.US_ASCII));
        byte[] inhalt = zufall().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer frei = ByteBuffer.allocate(8 + inhalt.length).putInt(8 + inhalt.length)
                .put("free".getBytes(StandardCharsets.US_ASCII)).put(inhalt);
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        aus.writeBytes(ftyp.array());
        aus.writeBytes(frei.array());
        return aus.toByteArray();
    }

    /** TIFF (Intel): Kopf, ein IFD mit einem Eintrag (Breite), danach Zufall. */
    public static byte[] tiff() {
        byte[] inhalt = zufall().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer puffer = ByteBuffer.allocate(8 + 18 + inhalt.length).order(ByteOrder.LITTLE_ENDIAN);
        puffer.put(new byte[]{'I', 'I', 42, 0}).putInt(8);
        puffer.putShort((short) 1).putShort((short) 256).putShort((short) 3).putInt(1).putShort((short) 1)
                .putShort((short) 0).putInt(0);
        puffer.put(inhalt);
        return puffer.array();
    }

    private static void chunk(ByteArrayOutputStream aus, String typ, byte[] daten) {
        byte[] name = typ.getBytes(StandardCharsets.US_ASCII);
        aus.writeBytes(ByteBuffer.allocate(4).putInt(daten.length).array());
        aus.writeBytes(name);
        aus.writeBytes(daten);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(daten);
        aus.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static String zufall() {
        byte[] bytes = new byte[16];
        ZUFALL.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
