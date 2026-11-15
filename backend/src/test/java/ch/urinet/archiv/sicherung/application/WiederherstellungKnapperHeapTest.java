package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.WiederherstellungsZiel.Quittung;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WiederherstellungKnapperHeapTest {

    private static final int OBJEKT = 0x55424a31;
    private static final int SHA256 = 0x53484131;
    private static final long GROESSE = 50L * 1024 * 1024;
    private static final Instant LAST_MODIFIED = Instant.parse("2026-01-01T00:00:00Z");
    private static final byte[] BLOCK = new byte[32 * 1024];

    @Test
    void stelltFuenfzigMiBObjektMitBegrenztemHeapWiederHer(@TempDir Path temp) throws Exception {
        Path strom = temp.resolve("strom.bin");
        String erwarteterHash = schreibeStrom(strom);
        WiederherstellungKnapperHeapZiel ziel = new WiederherstellungKnapperHeapZiel();
        SicherungBucketExport.InventarZeile inventar = new SicherungBucketExport.InventarZeile(
                SicherungBucketExport.INVENTAR_FORMAT, "archiv/gross.bin", "v1", false, GROESSE,
                LAST_MODIFIED.toString(), erwarteterHash, null, null);

        Wiederherstellung.Ergebnis ergebnis = new Wiederherstellung(ziel,
                Clock.fixed(LAST_MODIFIED, ZoneOffset.UTC))
                .wiederherstellen(new MandantCode("probe"), List.of(inventar), strom, false);

        assertThat(ergebnis.exitCode()).isZero();
        assertThat(ergebnis.zaehler().versionen()).isEqualTo(1);
        assertThat(ziel.bytes).isEqualTo(GROESSE);
        assertThat(ziel.maximalerLeseaufruf).isLessThanOrEqualTo(64 * 1024);
        assertThat(ziel.sha256).isEqualTo(erwarteterHash);
        assertThat(ziel.sperren).isZero();
        assertThat(ziel.verwerfen).isZero();
    }

    private static String schreibeStrom(Path strom) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DataOutputStream aus = new DataOutputStream(Files.newOutputStream(strom))) {
            aus.write(SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII));
            aus.writeInt(OBJEKT);
            text(aus, "archiv/gross.bin");
            text(aus, "v1");
            aus.writeLong(GROESSE);
            aus.writeLong(LAST_MODIFIED.toEpochMilli());
            long rest = GROESSE;
            while (rest > 0) {
                int laenge = (int) Math.min(rest, BLOCK.length);
                aus.write(BLOCK, 0, laenge);
                digest.update(BLOCK, 0, laenge);
                rest -= laenge;
            }
            byte[] sha256 = digest.digest();
            aus.writeInt(SHA256);
            aus.write(sha256);
            return HexFormat.of().formatHex(sha256);
        }
    }

    private static void text(DataOutputStream aus, String wert) throws IOException {
        byte[] daten = wert.getBytes(StandardCharsets.UTF_8);
        aus.writeInt(daten.length);
        aus.write(daten);
    }

    private static final class WiederherstellungKnapperHeapZiel implements WiederherstellungsZiel {
        private long bytes;
        private int maximalerLeseaufruf;
        private int sperren;
        private int verwerfen;
        private String sha256;

        @Override
        public Quittung schreiben(String schluessel, String versionId, Instant lastModified, InputStream inhalt,
                                  long groesse) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] puffer = new byte[64 * 1024];
                int gelesen;
                while ((gelesen = inhalt.read(puffer)) != -1) {
                    digest.update(puffer, 0, gelesen);
                    bytes += gelesen;
                    maximalerLeseaufruf = Math.max(maximalerLeseaufruf, gelesen);
                }
                sha256 = HexFormat.of().formatHex(digest.digest());
                return new Quittung("ziel-v1", sha256);
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void sperren(Quittung quittung, Sperrmodus modus, Instant bis) {
            sperren++;
        }

        @Override
        public void verwerfen(Quittung quittung) {
            verwerfen++;
        }

        @Override
        public String praefix() {
            return "urinet-archiv-p-";
        }

        @Override
        public boolean leer(MandantCode mandant) {
            return true;
        }
    }
}
