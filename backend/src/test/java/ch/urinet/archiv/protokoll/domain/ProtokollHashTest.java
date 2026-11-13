package ch.urinet.archiv.protokoll.domain;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Wurzel über die Blätter der Protokollzeilen (Req 15.1). */
class ProtokollHashTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void ohneZeileIstDieWurzelDerHashDerLeerenFolge() {
        assertThat(ProtokollHash.aus(new byte[0])).isEqualTo(new ProtokollHash(0,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
    }

    @Test
    void dieWurzelHaengtVonDerReihenfolgeDerBlaetterAb() throws Exception {
        byte[] a = MessageDigest.getInstance("SHA-256").digest(new byte[] {1});
        byte[] b = MessageDigest.getInstance("SHA-256").digest(new byte[] {2});
        ProtokollHash ab = ProtokollHash.aus(verbinden(a, b));
        assertThat(ab.anzahl()).isEqualTo(2);
        assertThat(ab.sha256()).isEqualTo(HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(verbinden(a, b))));
        assertThat(ProtokollHash.aus(verbinden(b, a)).sha256()).isNotEqualTo(ab.sha256());
        assertThatThrownBy(() -> ProtokollHash.aus(new byte[31])).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] verbinden(byte[] a, byte[] b) {
        byte[] ab = new byte[a.length + b.length];
        System.arraycopy(a, 0, ab, 0, a.length);
        System.arraycopy(b, 0, ab, a.length, b.length);
        return ab;
    }
}
