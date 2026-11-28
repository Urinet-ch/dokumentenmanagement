package ch.urinet.archiv.sftp;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Baut den SSH-Server des Scan-Eingangs (Spec 09, design.md Abschnitt 1, Requirements 1.2 und 1.3): nur das Subsystem
 * SFTP, keine Shell- und keine Command-Factory, Weiterleitungen abgelehnt, Anmeldung nur mit öffentlichem Schlüssel,
 * Host-Schlüssel nur aus der Datei, je Benutzer eine virtuelle Wurzel mit den Unterordnern des Scanners.
 */
public final class ScanServer {

    /** Unterordner je Scanprofil (Requirement 1.3); {@code fehler} schreibt nur der Worker. */
    public static final List<String> UNTERORDNER = List.of("stapel", "einzeln", "glas");

    private ScanServer() {
    }

    /** Der gebaute, noch nicht gestartete Server und seine Scan-Benutzer. */
    public record Eingang(SshServer server, List<String> benutzer) {
    }

    public static Eingang bauen(Konfiguration konfiguration, JsonLog log)
            throws IOException, GeneralSecurityException {
        Map<String, List<PublicKey>> schluessel = SchluesselDatei.lesen(konfiguration.schluesselDatei());
        List<KeyPair> hostSchluessel = hostSchluessel(konfiguration.hostSchluessel());

        SshServer server = SshServer.setUpDefaultServer();
        server.setPort(konfiguration.port());
        server.setKeyPairProvider(KeyPairProvider.wrap(hostSchluessel));

        // Anmeldung nur mit Schlüssel: keine Passwort-, Keyboard-Interactive-, GSS- oder Host-basierte Anmeldung
        server.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
        server.setPasswordAuthenticator(null);
        server.setKeyboardInteractiveAuthenticator(null);
        server.setGSSAuthenticator(null);
        server.setHostBasedAuthenticator(null);
        server.setPublickeyAuthenticator((benutzer, key, session) -> {
            session.setAttribute(Protokoll.BENUTZER, benutzer);
            List<PublicKey> erlaubt = schluessel.get(benutzer);
            boolean bekannt = erlaubt != null && erlaubt.stream().anyMatch(k -> KeyUtils.compareKeys(k, key));
            if (bekannt) {
                session.setAttribute(Protokoll.SCHLUESSELTYP, KeyUtils.getKeyType(key));
            }
            return bekannt;
        });

        // Nur SFTP: ohne Shell- und Command-Factory lehnt MINA «shell» und «exec» ab; keine Weiterleitung, kein Agent
        server.setShellFactory(null);
        server.setCommandFactory(null);
        server.setForwardingFilter(RejectAllForwardingFilter.INSTANCE);
        server.setAgentFactory(null);

        Protokoll protokoll = new Protokoll(log);
        SftpSubsystemFactory sftp = new SftpSubsystemFactory.Builder().build();
        sftp.addSftpEventListener(protokoll);
        server.setSubsystemFactories(List.of(sftp));
        server.addSessionListener(protokoll);

        VirtualFileSystemFactory dateisystem = new VirtualFileSystemFactory();
        for (String benutzer : schluessel.keySet()) {
            Path home = konfiguration.wurzel().resolve(benutzer);
            for (String unterordner : UNTERORDNER) {
                Files.createDirectories(home.resolve(unterordner));
            }
            dateisystem.setUserHomeDir(benutzer, home);
        }
        server.setFileSystemFactory(dateisystem);

        Verfahren.anwenden(server, konfiguration.aeltereVerfahren());
        return new Eingang(server, schluessel.keySet().stream().sorted().toList());
    }

    /** Liest den Host-Schlüssel; fehlt er, startet der Dienst nicht (er erzeugt nie selbst einen). */
    private static List<KeyPair> hostSchluessel(Path datei) throws IOException, GeneralSecurityException {
        if (!Files.isRegularFile(datei)) {
            throw new IllegalArgumentException("Host-Schlüssel fehlt: " + datei);
        }
        List<KeyPair> paare = new ArrayList<>();
        for (KeyPair paar : new FileKeyPairProvider(datei).loadKeys(null)) {
            paare.add(paar);
        }
        if (paare.isEmpty()) {
            throw new IllegalArgumentException("Host-Schlüssel nicht lesbar: " + datei);
        }
        return paare;
    }
}
