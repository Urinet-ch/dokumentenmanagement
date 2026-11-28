package ch.urinet.archiv.sftp;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Startet den Scan-Eingang in-process an einem freien Port, mit Schlüsseln, die nur für den Test erzeugt werden
 * (nie im Repo). Zwei Benutzer, damit die Trennung der Wurzeln prüfbar ist.
 */
final class TestEingang implements AutoCloseable {

    static final Duration WARTEN = Duration.ofSeconds(10);

    final Path wurzel;
    final KeyPair scanner;
    final KeyPair andere;
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    final SshServer server;
    final SshClient client;

    TestEingang(Path verzeichnis, List<String> aeltereVerfahren) throws IOException, GeneralSecurityException {
        wurzel = verzeichnis.resolve("eingang");
        scanner = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
        andere = KeyUtils.generateKeyPair(KeyPairProvider.ECDSA_SHA2_NISTP256, 256);
        Path host = verzeichnis.resolve("hostkey");
        try (OutputStream out = Files.newOutputStream(host)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(
                    KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256), "test", null, out);
        }
        Path schluessel = verzeichnis.resolve("schluessel");
        Files.writeString(schluessel, "# Testschlüssel\n"
                + "scan-test " + PublicKeyEntry.toString(scanner.getPublic()) + " geraet\n"
                + "scan-andere " + PublicKeyEntry.toString(andere.getPublic()) + "\n", StandardCharsets.UTF_8);
        Konfiguration konfiguration = new Konfiguration(2222, wurzel, host, schluessel, aeltereVerfahren);
        server = ScanServer.bauen(konfiguration, new JsonLog(log::add)).server();
        server.setPort(0);
        server.start();
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
    }

    ClientSession verbinden(String benutzer) throws IOException {
        return client.connect(benutzer, "127.0.0.1", server.getPort()).verify(WARTEN).getSession();
    }

    ClientSession anmelden(String benutzer, KeyPair schluessel) throws IOException {
        ClientSession session = verbinden(benutzer);
        session.addPublicKeyIdentity(schluessel);
        session.auth().verify(WARTEN);
        return session;
    }

    /** Wartet, bis eine Log-Zeile den Text enthält (der Server schreibt nach der Antwort an den Client). */
    String logZeile(String enthaelt) throws InterruptedException {
        long ende = System.nanoTime() + WARTEN.toNanos();
        while (System.nanoTime() < ende) {
            synchronized (log) {
                for (String zeile : log) {
                    if (zeile.contains(enthaelt)) {
                        return zeile;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Keine Log-Zeile mit " + enthaelt + " in " + log);
    }

    @Override
    public void close() throws IOException {
        client.stop();
        server.stop(true);
    }
}
