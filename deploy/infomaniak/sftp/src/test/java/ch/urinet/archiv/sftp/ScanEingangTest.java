package ch.urinet.archiv.sftp;

import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prototyp des Scan-Eingangs (Spec 09, Aufgabe 27.3) mit dem MINA-Client in-process: Upload in {@code stapel/}, nur
 * Schlüssel-Anmeldung, nur SFTP (Requirements 1.2, 1.3; Teilnachweis, der volle Nachweis folgt in Aufgabe 28.2).
 */
class ScanEingangTest {

    @TempDir
    Path tmp;

    private TestEingang eingang;

    @AfterEach
    void stoppen() throws IOException {
        if (eingang != null) {
            eingang.close();
        }
    }

    private TestEingang starten(List<String> aeltere) throws Exception {
        eingang = new TestEingang(tmp, aeltere);
        return eingang;
    }

    @Test
    void uploadInStapelGelingtUndWirdProtokolliert() throws Exception {
        TestEingang e = starten(List.of());
        byte[] inhalt = "%PDF-1.7\n% synthetischer Teststapel\n".getBytes();
        try (ClientSession session = e.anmelden("scan-test", e.scanner);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            try (OutputStream out = sftp.write("/stapel/scan-0001.pdf")) {
                out.write(inhalt);
            }
        }
        assertThat(e.wurzel.resolve("scan-test/stapel/scan-0001.pdf")).hasBinaryContent(inhalt);
        String datei = e.logZeile("\"ereignis\":\"datei\"");
        assertThat(datei).contains("\"benutzer\":\"scan-test\"", "\"unterordner\":\"stapel\"",
                "\"dateiname\":\"scan-0001.pdf\"", "\"groesse\":" + inhalt.length).doesNotContain("synthetisch");
        assertThat(e.logZeile("\"ergebnis\":\"angenommen\"")).contains("\"benutzer\":\"scan-test\"",
                "\"adresse\":\"127.0.0.1\"", "\"schluesseltyp\":\"ssh-ed25519\"");
        // Pfade nur relativ zur Wurzel des Benutzers, nie der Pfad im Container
        assertThat(String.join("\n", e.log)).doesNotContain(tmp.toString());
    }

    @Test
    void unterordnerJeBenutzerBeimStartAngelegt() throws Exception {
        TestEingang e = starten(List.of());
        for (String benutzer : List.of("scan-test", "scan-andere")) {
            for (String ordner : List.of("stapel", "einzeln", "glas")) {
                assertThat(e.wurzel.resolve(benutzer).resolve(ordner)).isDirectory();
            }
        }
        assertThat(e.wurzel.resolve("scan-test/fehler")).doesNotExist();
    }

    @Test
    void wurzelJeBenutzerOhneWegNachAussen() throws Exception {
        TestEingang e = starten(List.of());
        try (ClientSession session = e.anmelden("scan-test", e.scanner);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            assertThat(sftp.canonicalPath("/../..")).isEqualTo("/");
            try (OutputStream out = sftp.write("/../scan-andere/stapel/fremd.pdf")) {
                out.write(1);
            } catch (IOException erwartet) {
                // der Pfad liegt in der eigenen Wurzel und existiert dort nicht
            }
        }
        try (var dateien = Files.list(e.wurzel.resolve("scan-andere/stapel"))) {
            assertThat(dateien).isEmpty();
        }
    }

    @Test
    void fremderSchluesselAbgelehnt() throws Exception {
        TestEingang e = starten(List.of());
        try (ClientSession session = e.verbinden("scan-test")) {
            session.addPublicKeyIdentity(e.andere);
            assertThatThrownBy(() -> session.auth().verify(TestEingang.WARTEN)).isInstanceOf(SshException.class);
        }
        assertThat(e.logZeile("\"ergebnis\":\"abgelehnt\"")).contains("\"benutzer\":\"scan-test\"");
    }

    @Test
    void passwortAbgelehnt() throws Exception {
        TestEingang e = starten(List.of());
        try (ClientSession session = e.verbinden("scan-test")) {
            session.addPasswordIdentity("geheim");
            assertThatThrownBy(() -> session.auth().verify(TestEingang.WARTEN)).isInstanceOf(SshException.class);
        }
    }

    @Test
    void keyboardInteractiveAbgelehnt() throws Exception {
        TestEingang e = starten(List.of());
        try (ClientSession session = e.verbinden("scan-test")) {
            session.setUserAuthFactories(List.of(new UserAuthKeyboardInteractiveFactory()));
            session.setUserInteraction(new UserInteraction() {
                @Override
                public String[] interactive(ClientSession s, String name, String instruction, String lang,
                        String[] prompt, boolean[] echo) {
                    return new String[] {"geheim"};
                }

                @Override
                public String getUpdatedPassword(ClientSession s, String prompt, String lang) {
                    return null;
                }
            });
            assertThatThrownBy(() -> session.auth().verify(TestEingang.WARTEN)).isInstanceOf(SshException.class);
        }
    }

    @Test
    void shellAbgelehnt() throws Exception {
        TestEingang e = starten(List.of());
        try (ClientSession session = e.anmelden("scan-test", e.scanner)) {
            CoreModuleProperties.REQUEST_SHELL_REPLY.set(session, true);
            try (ChannelShell shell = session.createShellChannel()) {
                assertAbgelehnt(shell);
            }
        }
    }

    @Test
    void execAbgelehnt() throws Exception {
        TestEingang e = starten(List.of());
        try (ClientSession session = e.anmelden("scan-test", e.scanner)) {
            CoreModuleProperties.REQUEST_EXEC_REPLY.set(session, true);
            try (ChannelExec exec = session.createExecChannel("ls /")) {
                assertAbgelehnt(exec);
            }
        }
    }

    @Test
    void sha1VerfahrenNurUeberDieListe() throws Exception {
        String sha1 = BuiltinDHFactories.dhg14.getName();
        TestEingang ohne = starten(List.of());
        assertThat(Verfahren.namen(ohne.server).values()).allSatisfy(namen ->
                assertThat(namen).noneMatch(Verfahren::istAelter));
        assertThatThrownBy(() -> nurMit(ohne, sha1)).isInstanceOf(SshException.class);
        assertThat(ohne.logZeile("keine Einigung")).contains(sha1);
        ohne.close();
        eingang = null;

        TestEingang mit = starten(List.of(sha1));
        try (ClientSession session = nurMit(mit, sha1)) {
            assertThat(session.isAuthenticated()).isTrue();
        }
        assertThat(mit.logZeile("\"ausgehandelt\"")).contains(sha1);
    }

    /** Client, der als Schlüsseltausch nur {@code kex} anbietet, wie ein älteres Gerät. */
    private static ClientSession nurMit(TestEingang e, String kex) throws IOException {
        e.client.setKeyExchangeFactories(List.of(ClientBuilder.DH2KEX.apply(BuiltinDHFactories.fromFactoryName(kex))));
        ClientSession session = e.verbinden("scan-test");
        session.addPublicKeyIdentity(e.scanner);
        try {
            session.auth().verify(TestEingang.WARTEN);
        } catch (IOException | RuntimeException fehler) {
            session.close(true);
            throw fehler;
        }
        return session;
    }

    /** Der Server lehnt die Anfrage ab: Öffnen scheitert oder der Kanal schliesst, ohne etwas auszuführen. */
    private static void assertAbgelehnt(ClientChannel kanal) {
        boolean offen;
        try {
            kanal.open().verify(TestEingang.WARTEN);
            offen = true;
        } catch (IOException | RuntimeException abgelehnt) {
            offen = false;
        }
        if (offen) {
            Set<ClientChannelEvent> ereignisse = kanal.waitFor(EnumSet.of(ClientChannelEvent.CLOSED),
                    TestEingang.WARTEN);
            assertThat(ereignisse).contains(ClientChannelEvent.CLOSED);
        }
        assertThat(kanal.getExitStatus()).isNull();
    }
}
