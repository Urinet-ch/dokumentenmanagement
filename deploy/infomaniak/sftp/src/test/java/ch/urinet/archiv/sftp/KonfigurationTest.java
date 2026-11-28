package ch.urinet.archiv.sftp;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Konfiguration, Schlüsseldatei, Host-Schlüssel und Liste älterer Verfahren (Aufgabe 27.3). */
class KonfigurationTest {

    @TempDir
    Path tmp;

    @Test
    void standardwerteOhneAeltereVerfahren() {
        Konfiguration k = Konfiguration.aus(Map.of());
        assertThat(k.port()).isEqualTo(2222);
        assertThat(k.wurzel()).isEqualTo(Path.of("/srv/eingang"));
        assertThat(k.hostSchluessel()).isEqualTo(Path.of("/run/secrets/archiv_sftp_hostkey"));
        assertThat(k.schluesselDatei()).isEqualTo(Path.of("/run/secrets/archiv_sftp_schluessel"));
        assertThat(k.aeltereVerfahren()).isEmpty();
        assertThat(Konfiguration.aus(Map.of(Konfiguration.AELTERE_VERFAHREN, " ssh-rsa, ,hmac-sha1 "))
                .aeltereVerfahren()).containsExactly("ssh-rsa", "hmac-sha1");
    }

    @Test
    void portNurUeber1024() {
        assertThatThrownBy(() -> Konfiguration.aus(Map.of(Konfiguration.PORT, "22")))
                .hasMessageContaining("ohne Root");
        assertThatThrownBy(() -> Konfiguration.aus(Map.of(Konfiguration.PORT, "zwei")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schluesseldateiPrueftBenutzerUndSchluessel() throws Exception {
        String key = PublicKeyEntry.toString(KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256).getPublic());
        Path datei = tmp.resolve("schluessel");

        Files.writeString(datei, "\n# Kommentar\nscan-urinet " + key + " geraet\nscan-urinet " + key + "\n");
        assertThat(SchluesselDatei.lesen(datei)).containsOnlyKeys("scan-urinet")
                .extractingByKey("scan-urinet").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(2);

        Files.writeString(datei, "root " + key + "\n");
        assertThatThrownBy(() -> SchluesselDatei.lesen(datei)).hasMessageContaining("scan-<mandant>");

        Files.writeString(datei, "scan-urinet ssh-ed25519 AAAAkaputt\n");
        assertThatThrownBy(() -> SchluesselDatei.lesen(datei)).hasMessageContaining("Zeile 1")
                .hasMessageNotContaining("AAAAkaputt");

        Files.writeString(datei, "# leer\n");
        assertThatThrownBy(() -> SchluesselDatei.lesen(datei)).hasMessageContaining("keinen Scan-Benutzer");
    }

    @Test
    void ohneHostSchluesselKeinStart() throws Exception {
        String key = PublicKeyEntry.toString(KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256).getPublic());
        Path schluessel = Files.writeString(tmp.resolve("schluessel"), "scan-test " + key + "\n");
        Path host = tmp.resolve("fehlt");
        Konfiguration k = new Konfiguration(2222, tmp.resolve("eingang"), host, schluessel, List.of());
        assertThatThrownBy(() -> ScanServer.bauen(k, new JsonLog(z -> { })))
                .hasMessageContaining("Host-Schlüssel fehlt");
        assertThat(host).doesNotExist();
    }

    @Test
    void aeltereVerfahrenNurBekannteUndHinten() {
        SshServer server = SshServer.setUpDefaultServer();
        Verfahren.anwenden(server, List.of("ssh-rsa", "diffie-hellman-group14-sha1", "hmac-sha1"));
        Map<String, List<String>> namen = Verfahren.namen(server);
        assertThat(namen.get("signatur")).endsWith("ssh-rsa");
        assertThat(namen.get("schluesseltausch")).endsWith("diffie-hellman-group14-sha1");
        assertThat(namen.get("mac")).endsWith("hmac-sha1");
        assertThat(namen.get("verschluesselung")).noneMatch(Verfahren::istAelter);

        assertThatThrownBy(() -> Verfahren.anwenden(SshServer.setUpDefaultServer(), List.of("ssh-rza")))
                .hasMessageContaining("unbekannte Verfahren [ssh-rza]");
        assertThatThrownBy(() -> Verfahren.anwenden(SshServer.setUpDefaultServer(), List.of("none")))
                .hasMessageContaining("[none]");
    }

    @Test
    void jsonLogMaskiertSonderzeichen() {
        assertThat(JsonLog.json(Map.of("a", "x\"y\\z\n\u0001"))).isEqualTo("{\"a\":\"x\\\"y\\\\z\\n\\u0001\"}");
        assertThat(JsonLog.json(List.of(1, true, "b"))).isEqualTo("[1,true,\"b\"]");
    }
}
