package ch.urinet.archiv.driveimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenDateiTest {

    /** Synthetisches Probe-Token im Format ua_<id>_<geheimnis>, zur Laufzeit zusammengesetzt. */
    static final String TOKEN = String.join("_", "ua", "abcdefghijk1", "A".repeat(43));

    @TempDir
    Path tmp;

    @Test
    void liestTokenAusDateiMitRechten600() throws Exception {
        Path datei = datei(TOKEN + "\n", "rw-------");
        assertThat(TokenDatei.lesen(datei)).isEqualTo(TOKEN);
    }

    @Test
    void verweigertLockereRechteOhneDenInhaltZuNennen() throws Exception {
        for (String rechte : new String[] {"rw-r-----", "rw----r--", "rw-rw-rw-", "rw-----w-"}) {
            Path datei = datei(TOKEN, rechte);
            assertThatThrownBy(() -> TokenDatei.lesen(datei)).isInstanceOf(TokenDatei.Ungueltig.class)
                    .hasMessageNotContaining(TOKEN.substring(16));
        }
    }

    @Test
    void verweigertLinkFehlendeDateiUndFalschesFormat() throws Exception {
        Path echt = datei(TOKEN, "rw-------");
        Path link = Files.createSymbolicLink(tmp.resolve("link"), echt);
        assertThatThrownBy(() -> TokenDatei.lesen(link)).hasMessageContaining("keine gewöhnliche Datei");
        assertThatThrownBy(() -> TokenDatei.lesen(tmp.resolve("fehlt"))).hasMessageContaining("nicht gefunden");
        Path falsch = datei("geheim-aber-falsch", "rw-------");
        assertThatThrownBy(() -> TokenDatei.lesen(falsch)).hasMessageContaining("kein gültiges API-Token")
                .hasMessageNotContaining("geheim-aber-falsch");
    }

    @Test
    void pfadAusOptionUmgebungOderStandard() {
        Path option = Path.of("/a/token");
        assertThat(TokenDatei.pfad(option, Map.of(TokenDatei.UMGEBUNG, "/b"), "/home/x")).isEqualTo(option);
        assertThat(TokenDatei.pfad(null, Map.of(TokenDatei.UMGEBUNG, "/b/token"), "/home/x"))
                .isEqualTo(Path.of("/b/token"));
        assertThat(TokenDatei.pfad(null, Map.of(), "/home/x"))
                .isEqualTo(Path.of("/home/x/.config/urinet-archiv/token"));
    }

    private Path datei(String inhalt, String rechte) throws Exception {
        Path datei = Files.createTempFile(tmp, "token", "");
        Files.writeString(datei, inhalt);
        Files.setPosixFilePermissions(datei, PosixFilePermissions.fromString(rechte));
        return datei;
    }
}
