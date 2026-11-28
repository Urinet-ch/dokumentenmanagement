package ch.urinet.archiv.stapel;

import ch.urinet.archiv.sftp.JsonLog;
import ch.urinet.archiv.sftp.Konfiguration;
import ch.urinet.archiv.sftp.ScanServer;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schritte zum Scan-Eingang (Spec 09, Requirements 1.2 und 1.3; Aufgabe 27.3). Der Dienst {@code sftp} aus
 * {@code deploy/infomaniak/sftp} läuft in-process mit dem MINA-Client als Gegenstelle; Schlüssel und Dateien sind
 * synthetisch, liegen in einem Temp-Ordner und werden aufgeräumt.
 */
public class ScanEingangSchritte {

    private static final Duration WARTEN = Duration.ofSeconds(10);
    private static final byte[] INHALT = "%PDF-1.7\n% synthetischer Teststapel\n".getBytes(StandardCharsets.US_ASCII);

    private Path verzeichnis;
    private SshServer server;
    private SshClient client;
    private final Map<String, KeyPair> schluessel = new LinkedHashMap<>();
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private ClientSession session;
    private boolean angenommen;

    @Angenommen("der Scan-Eingang läuft mit den Benutzern «{word}» und «{word}»")
    public void eingangLaeuft(String erster, String zweiter) throws IOException, GeneralSecurityException {
        verzeichnis = Files.createTempDirectory("scan-eingang");
        StringBuilder zeilen = new StringBuilder();
        for (String benutzer : List.of(erster, zweiter)) {
            KeyPair paar = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
            schluessel.put(benutzer, paar);
            zeilen.append(benutzer).append(' ').append(PublicKeyEntry.toString(paar.getPublic())).append('\n');
        }
        Path datei = Files.writeString(verzeichnis.resolve("schluessel"), zeilen, StandardCharsets.UTF_8);
        Path host = verzeichnis.resolve("hostkey");
        try (OutputStream out = Files.newOutputStream(host)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(
                    KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256), "test", null, out);
        }
        Konfiguration konfiguration = new Konfiguration(2222, verzeichnis.resolve("eingang"), host, datei, List.of());
        server = ScanServer.bauen(konfiguration, new JsonLog(log::add)).server();
        server.setPort(0);
        server.start();
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
    }

    @After
    public void aufraeumen() throws IOException {
        if (client != null) {
            client.stop();
        }
        if (server != null) {
            server.stop(true);
        }
        if (verzeichnis != null) {
            try (Stream<Path> pfade = Files.walk(verzeichnis)) {
                pfade.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
    }

    @Wenn("sich «{word}» mit seinem Schlüssel anmeldet")
    public void mitSchluessel(String benutzer) throws IOException {
        anmelden(benutzer, s -> s.addPublicKeyIdentity(schluessel.get(benutzer)));
    }

    @Wenn("sich «{word}» mit dem Schlüssel von «{word}» anmeldet")
    public void mitFremdemSchluessel(String benutzer, String anderer) throws IOException {
        anmelden(benutzer, s -> s.addPublicKeyIdentity(schluessel.get(anderer)));
    }

    @Wenn("sich «{word}» mit einem Passwort anmeldet")
    public void mitPasswort(String benutzer) throws IOException {
        anmelden(benutzer, s -> s.addPasswordIdentity("geheim"));
    }

    @Wenn("sich «{word}» mit Keyboard-Interactive anmeldet")
    public void mitKeyboardInteractive(String benutzer) throws IOException {
        anmelden(benutzer, s -> {
            s.setUserAuthFactories(List.of(new UserAuthKeyboardInteractiveFactory()));
            s.setUserInteraction(new UserInteraction() {
                @Override
                public String[] interactive(ClientSession c, String name, String instruction, String lang,
                        String[] prompt, boolean[] echo) {
                    String[] antworten = new String[prompt.length];
                    Arrays.fill(antworten, "geheim");
                    return antworten;
                }

                @Override
                public String getUpdatedPassword(ClientSession c, String prompt, String lang) {
                    return null;
                }
            });
        });
    }

    @Dann("ist die Anmeldung angenommen")
    public void anmeldungAngenommen() {
        assertThat(angenommen).isTrue();
    }

    @Dann("ist die Anmeldung abgelehnt")
    public void anmeldungAbgelehnt() {
        assertThat(angenommen).isFalse();
    }

    @Dann("das Log des Scan-Eingangs nennt für «{word}» eine angenommene und eine abgelehnte Anmeldung")
    public void logAnmeldungen(String benutzer) throws InterruptedException {
        assertThat(logZeile("\"ergebnis\":\"angenommen\"")).contains("\"benutzer\":\"" + benutzer + "\"",
                "\"adresse\":\"127.0.0.1\"");
        assertThat(logZeile("\"ergebnis\":\"abgelehnt\"")).contains("\"ereignis\":\"anmeldung\"");
    }

    @Dann("lehnt der Scan-Eingang eine Shell ab")
    public void shellAbgelehnt() throws IOException {
        CoreModuleProperties.REQUEST_SHELL_REPLY.set(session, true);
        try (ChannelShell shell = session.createShellChannel()) {
            assertAbgelehnt(shell);
        }
    }

    @Dann("lehnt der Scan-Eingang den Befehl «{}» ab")
    public void befehlAbgelehnt(String befehl) throws IOException {
        CoreModuleProperties.REQUEST_EXEC_REPLY.set(session, true);
        try (ChannelExec exec = session.createExecChannel(befehl)) {
            assertAbgelehnt(exec);
        }
    }

    @Dann("lehnt der Scan-Eingang eine Weiterleitung von Ports ab")
    public void weiterleitungAbgelehnt() throws IOException {
        SshdSocketAddress ziel = new SshdSocketAddress("127.0.0.1", server.getPort());
        try (var direkt = session.createDirectTcpipChannel(new SshdSocketAddress("127.0.0.1", 0), ziel)) {
            assertThatThrownBy(() -> direkt.open().verify(WARTEN)).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> session.startRemotePortForwarding(new SshdSocketAddress("127.0.0.1", 0), ziel))
                .isInstanceOf(IOException.class);
    }

    @Dann("zeigt die Wurzel von «{word}» nur die Ordner «{}»")
    public void wurzelZeigt(String benutzer, String ordner) throws IOException {
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            List<String> namen = StreamSupport.stream(sftp.readDir("/").spliterator(), false)
                    .map(SftpClient.DirEntry::getFilename).filter(n -> !n.equals(".") && !n.equals("..")).sorted()
                    .toList();
            assertThat(namen).containsExactly(ordner.split(",\\s*"));
            assertThat(sftp.canonicalPath("/../..")).isEqualTo("/");
        }
        assertThat(session.getUsername()).isEqualTo(benutzer);
    }

    @Wenn("«{word}» die Datei «{}» hochlädt")
    public void hochladen(String benutzer, String pfad) throws IOException {
        assertThat(session.getUsername()).isEqualTo(benutzer);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
             OutputStream out = sftp.write(pfad)) {
            out.write(INHALT);
        }
    }

    @Wenn("«{word}» die Datei «{}» hochzuladen versucht")
    public void hochladenVersuchen(String benutzer, String pfad) {
        try {
            hochladen(benutzer, pfad);
        } catch (IOException erwartet) {
            // Der Pfad liegt in der eigenen Wurzel und existiert dort nicht
        }
    }

    @Dann("liegt die Datei «{}» im Ordner «{word}» von «{word}»")
    public void dateiLiegt(String name, String ordner, String benutzer) {
        assertThat(verzeichnis.resolve("eingang").resolve(benutzer).resolve(ordner).resolve(name))
                .hasBinaryContent(INHALT);
    }

    @Dann("das Log des Scan-Eingangs nennt die Datei «{}» im Unterordner «{word}» mit ihrer Grösse")
    public void logDatei(String name, String ordner) throws InterruptedException {
        assertThat(logZeile("\"ereignis\":\"datei\"")).contains("\"dateiname\":\"" + name + "\"",
                "\"unterordner\":\"" + ordner + "\"", "\"groesse\":" + INHALT.length)
                .doesNotContain("synthetisch", verzeichnis.toString());
    }

    @Dann("liegt im Eingang von «{word}» keine Datei")
    public void keineDatei(String benutzer) throws IOException {
        try (Stream<Path> pfade = Files.walk(verzeichnis.resolve("eingang").resolve(benutzer))) {
            assertThat(pfade.filter(Files::isRegularFile)).isEmpty();
        }
    }

    private interface Anmeldung {
        void vorbereiten(ClientSession session) throws IOException;
    }

    private void anmelden(String benutzer, Anmeldung art) throws IOException {
        if (session != null) {
            session.close(true);
        }
        session = client.connect(benutzer, "127.0.0.1", server.getPort()).verify(WARTEN).getSession();
        art.vorbereiten(session);
        try {
            session.auth().verify(WARTEN);
            angenommen = true;
        } catch (IOException abgelehnt) {
            angenommen = false;
        }
    }

    private String logZeile(String enthaelt) throws InterruptedException {
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
        throw new AssertionError("Keine Log-Zeile mit " + enthaelt);
    }

    /** Öffnen scheitert oder der Kanal schliesst, ohne etwas auszuführen. */
    private static void assertAbgelehnt(ClientChannel kanal) {
        boolean offen;
        try {
            kanal.open().verify(WARTEN);
            offen = true;
        } catch (IOException | RuntimeException abgelehnt) {
            offen = false;
        }
        if (offen) {
            assertThat(kanal.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), WARTEN))
                    .contains(ClientChannelEvent.CLOSED);
        }
        assertThat(kanal.getExitStatus()).isNull();
    }
}
