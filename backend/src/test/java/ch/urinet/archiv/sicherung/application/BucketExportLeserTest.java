package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.sicherung.application.BucketExportLeser.Objekt;
import ch.urinet.archiv.sicherung.application.BucketExportLeser.Pruefstatus;
import ch.urinet.archiv.sicherung.application.BucketExportLeser.Pruefung;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BucketExportLeserTest {

    private static final int OBJEKT = 0x55424a31;
    private static final int SHA256 = 0x53484131;

    @Test
    void liestKonkateniertenStromUndBegrenztDenObjektinhalt() throws Exception {
        byte[] erster = frame("archiv/a", "v1", "eins".getBytes(StandardCharsets.UTF_8), false);
        byte[] zweiter = frame("manifest/b", "v2", "zwei".getBytes(StandardCharsets.UTF_8), false);
        byte[] daten = zusammen(SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII),
                erster, SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII), zweiter);
        BucketExportLeser leser = new BucketExportLeser(new ByteArrayInputStream(daten));

        Objekt a = leser.naechstes().orElseThrow();
        assertThat(a.schluessel()).isEqualTo("archiv/a");
        assertThat(a.versionId()).isEqualTo("v1");
        assertThat(a.inhalt().readAllBytes()).containsExactly("eins".getBytes(StandardCharsets.UTF_8));
        assertThat(a.pruefsummePruefen().status()).isEqualTo(Pruefstatus.BESTAETIGT);

        Objekt b = leser.naechstes().orElseThrow();
        assertThat(b.schluessel()).isEqualTo("manifest/b");
        assertThat(b.inhalt().readAllBytes()).containsExactly("zwei".getBytes(StandardCharsets.UTF_8));
        assertThat(b.pruefsummePruefen().bestaetigt()).isTrue();
        assertThat(leser.naechstes()).isEmpty();
    }

    @Test
    void lehntFehlendenUndFalschenHeaderSowieFalscheObjektkennungAb() {
        assertFormatFehler(new byte[0]);
        assertFormatFehler("FALSCH\n".getBytes(StandardCharsets.US_ASCII));
        assertFormatFehler(zusammen(SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII),
                new byte[]{1, 2, 3, 4}));
    }

    @Test
    void meldetAbgeschnittenenInhaltUndTrailerAlsFormatfehler() throws Exception {
        byte[] abgeschnitten = withHeader(frameMitGroesse("archiv/a", "v1", new byte[]{1, 2}, 10, true, false));
        BucketExportLeser leser = new BucketExportLeser(new ByteArrayInputStream(abgeschnitten));
        Objekt objekt = leser.naechstes().orElseThrow();
        assertThatThrownBy(objekt::pruefsummePruefen)
                .isInstanceOf(SicherungExportFehler.class)
                .hasMessage("Ungültiges Bucket-Exportformat");

        byte[] trailerFehlt = withHeader(frameMitGroesse("archiv/a", "v1", new byte[]{1}, 1, false, false));
        Objekt ohneTrailer = new BucketExportLeser(new ByteArrayInputStream(trailerFehlt)).naechstes().orElseThrow();
        assertThatThrownBy(ohneTrailer::pruefsummePruefen).isInstanceOf(SicherungExportFehler.class);
    }

    @Test
    void meldetAbweichendenHashUndFalscheTrailerkennung() throws Exception {
        byte[] hashFalsch = withHeader(frame("archiv/a", "v1", new byte[]{1, 2, 3}, true));
        BucketExportLeser leser = new BucketExportLeser(new ByteArrayInputStream(hashFalsch));
        Objekt objekt = leser.naechstes().orElseThrow();
        objekt.inhalt().readAllBytes();
        Pruefung pruefung = objekt.pruefsummePruefen();
        assertThat(pruefung.status()).isEqualTo(Pruefstatus.ABWEICHEND);
        assertThat(pruefung.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(new byte[]{1, 2, 3})));

        byte[] trailerUngueltig = withHeader(frameMitTrailerkennung(0x12345678));
        BucketExportLeser fehlerhafterLeser = new BucketExportLeser(new ByteArrayInputStream(trailerUngueltig));
        Objekt fehlerhaftesObjekt = fehlerhafterLeser.naechstes().orElseThrow();
        assertThatThrownBy(fehlerhaftesObjekt::pruefsummePruefen)
                .isInstanceOf(SicherungExportFehler.class);
    }

    @Test
    void lehntNegativeZuGrosseGroesseUndZuLangenSchluesselAb() {
        assertFormatFehler(withHeader(frameMitGroesse("archiv/a", "v1", new byte[0], -1, false, true)));
        assertFormatFehler(withHeader(frameMitGroesse("archiv/a", "v1", new byte[0], (1L << 40) + 1, false, true)));
        assertFormatFehler(withHeader(frameMitSchluessellaenge(1025)));
    }

    private static void assertFormatFehler(byte[] daten) {
        BucketExportLeser leser = new BucketExportLeser(new ByteArrayInputStream(daten));
        assertThatThrownBy(leser::naechstes).isInstanceOf(SicherungExportFehler.class)
                .hasMessage("Ungültiges Bucket-Exportformat");
    }

    private static byte[] frame(String schluessel, String versionId, byte[] daten, boolean falscherHash)
            throws Exception {
        return frameMitGroesse(schluessel, versionId, daten, daten.length, falscherHash, true);
    }

    private static byte[] frameMitGroesse(String schluessel, String versionId, byte[] daten, long groesse,
                                          boolean falscherHash, boolean trailer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream aus = new DataOutputStream(bytes)) {
                aus.write(SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII));
                aus.writeInt(OBJEKT);
                text(aus, schluessel);
                text(aus, versionId);
                aus.writeLong(groesse);
                aus.writeLong(InstantTestzeit.MILLIS);
                aus.write(daten);
                if (trailer) {
                    aus.writeInt(SHA256);
                    byte[] hash = MessageDigest.getInstance("SHA-256").digest(daten);
                    if (falscherHash) {
                        hash[0] ^= 0xff;
                    }
                    aus.write(hash);
                }
            }
            byte[] alles = bytes.toByteArray();
            return java.util.Arrays.copyOfRange(alles, SicherungBucketExport.BUCKET_FORMAT.length(), alles.length);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] frameMitSchluessellaenge(int laenge) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream aus = new DataOutputStream(bytes)) {
                aus.write(SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII));
                aus.writeInt(OBJEKT);
                aus.writeInt(laenge);
            }
            byte[] alles = bytes.toByteArray();
            return java.util.Arrays.copyOfRange(alles, SicherungBucketExport.BUCKET_FORMAT.length(), alles.length);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] frameMitTrailerkennung(int kennung) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream aus = new DataOutputStream(bytes)) {
            aus.write(SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII));
            aus.writeInt(OBJEKT);
            text(aus, "archiv/a");
            text(aus, "v1");
            aus.writeLong(0);
            aus.writeLong(InstantTestzeit.MILLIS);
            aus.writeInt(kennung);
            aus.write(new byte[32]);
        }
        byte[] alles = bytes.toByteArray();
        return java.util.Arrays.copyOfRange(alles, SicherungBucketExport.BUCKET_FORMAT.length(), alles.length);
    }

    private static void text(DataOutputStream aus, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        aus.writeInt(bytes.length);
        aus.write(bytes);
    }

    private static byte[] zusammen(byte[]... teile) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] teil : teile) {
            bytes.writeBytes(teil);
        }
        return bytes.toByteArray();
    }

    private static byte[] withHeader(byte[] frame) {
        return zusammen(SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII), frame);
    }

    private static final class InstantTestzeit {
        private static final long MILLIS = 1_798_761_600_000L;

        private InstantTestzeit() {
        }
    }
}
