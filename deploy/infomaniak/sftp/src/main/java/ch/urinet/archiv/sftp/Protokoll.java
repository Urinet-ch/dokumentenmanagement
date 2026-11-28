package ch.urinet.archiv.sftp;

import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.kex.KexProposalOption;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.DirectoryHandle;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.Handle;
import org.apache.sshd.sftp.server.SftpEventListener;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Protokoll des Scan-Eingangs (Requirement 1.7): Anmeldungen, ausgehandelte Verfahren, geschriebene Dateien und für
 * den Spike (27.4, Requirement 0.3) jede SFTP-Operation, die den Inhalt des Eingangs verändert. Pfade erscheinen als
 * Pfad in der virtuellen Wurzel des Benutzers ({@code /stapel/…}), nie als Pfad im Container.
 */
final class Protokoll implements SessionListener, SftpEventListener {

    static final AttributeRepository.AttributeKey<String> SCHLUESSELTYP = new AttributeRepository.AttributeKey<>();
    /** Benutzername eines Schlüssel-Versuchs; MINA setzt {@code getUsername()} erst nach erfolgreicher Anmeldung. */
    static final AttributeRepository.AttributeKey<String> BENUTZER = new AttributeRepository.AttributeKey<>();

    private final JsonLog log;

    Protokoll(JsonLog log) {
        this.log = log;
    }

    // --- Sitzung -------------------------------------------------------------------------------------------------

    @Override
    public void sessionNegotiationEnd(Session session, Map<KexProposalOption, String> client,
            Map<KexProposalOption, String> server, Map<KexProposalOption, String> ausgehandelt, Throwable fehler) {
        if (fehler == null) {
            log.ereignis("verfahren", "adresse", adresse(session), "ausgehandelt", namen(ausgehandelt));
        } else {
            // Die Vorschläge des Geräts helfen im Spike, die Liste älterer Verfahren zu bestimmen
            log.ereignis("verfahren", "adresse", adresse(session), "ergebnis", "keine Einigung",
                    "angebotGeraet", namen(client), "grund", fehler.getMessage());
        }
    }

    @Override
    public void sessionEvent(Session session, Event event) {
        if (event == Event.Authenticated) {
            log.ereignis("anmeldung", "benutzer", session.getUsername(), "adresse", adresse(session),
                    "ergebnis", "angenommen", "schluesseltyp", session.getAttribute(SCHLUESSELTYP));
        }
    }

    @Override
    public void sessionClosed(Session session) {
        if (!session.isAuthenticated()) {
            log.ereignis("anmeldung", "benutzer", session.getAttribute(BENUTZER), "adresse", adresse(session),
                    "ergebnis", "abgelehnt");
        }
    }

    // --- SFTP ----------------------------------------------------------------------------------------------------

    @Override
    public void open(ServerSession session, String remoteHandle, Handle handle) {
        if (handle instanceof FileHandle datei) {
            boolean schreiben = datei.getOpenOptions().contains(StandardOpenOption.WRITE);
            operation(session, schreiben ? "oeffnen-schreiben" : "oeffnen-lesen", datei.getFile(), null, null);
        } else if (handle instanceof DirectoryHandle verzeichnis) {
            operation(session, "verzeichnis-lesen", verzeichnis.getFile(), null, null);
        }
    }

    @Override
    public void openFailed(ServerSession session, String remotePath, Path localPath, boolean isDirectory,
            Throwable fehler) {
        operation(session, isDirectory ? "verzeichnis-oeffnen" : "oeffnen", localPath, null, fehler);
    }

    @Override
    public void closed(ServerSession session, String remoteHandle, Handle handle, Throwable fehler) {
        if (handle instanceof FileHandle datei && datei.getOpenOptions().contains(StandardOpenOption.WRITE)) {
            Path pfad = datei.getFile();
            Long groesse = null;
            try {
                groesse = Files.size(pfad);
            } catch (IOException e) {
                // Datei schon weg; die Grösse bleibt offen
            }
            log.ereignis("datei", "benutzer", session.getUsername(), "unterordner", unterordner(pfad),
                    "dateiname", name(pfad), "groesse", groesse, "ergebnis", fehler == null ? "ok" : "fehler");
        }
    }

    @Override
    public void created(ServerSession session, Path pfad, Map<String, ?> attrs, Throwable fehler) {
        operation(session, "verzeichnis-anlegen", pfad, null, fehler);
    }

    @Override
    public void moved(ServerSession session, Path von, Path nach, Collection<CopyOption> optionen,
            Throwable fehler) {
        operation(session, "umbenennen", von, nach, fehler);
    }

    @Override
    public void removed(ServerSession session, Path pfad, boolean verzeichnis, Throwable fehler) {
        operation(session, verzeichnis ? "verzeichnis-loeschen" : "loeschen", pfad, null, fehler);
    }

    @Override
    public void linked(ServerSession session, Path quelle, Path ziel, boolean symbolisch, Throwable fehler) {
        operation(session, symbolisch ? "symlink" : "link", quelle, ziel, fehler);
    }

    @Override
    public void modifiedAttributes(ServerSession session, Path pfad, Map<String, ?> attrs, Throwable fehler) {
        operation(session, "attribute-setzen", pfad, null, fehler);
    }

    private void operation(ServerSession session, String operation, Path pfad, Path ziel, Throwable fehler) {
        log.ereignis("sftp", "benutzer", session.getUsername(), "operation", operation,
                "pfad", pfad == null ? null : pfad.toString(), "ziel", ziel == null ? null : ziel.toString(),
                "ergebnis", fehler == null ? "ok" : "fehler");
    }

    private static String unterordner(Path pfad) {
        return pfad.getNameCount() > 1 ? pfad.getName(0).toString() : "";
    }

    private static String name(Path pfad) {
        Path n = pfad.getFileName();
        return n == null ? "" : n.toString();
    }

    private static String adresse(Session session) {
        SocketAddress a = session.getIoSession().getRemoteAddress();
        if (a instanceof InetSocketAddress i && i.getAddress() != null) {
            return i.getAddress().getHostAddress();
        }
        return a == null ? null : a.toString();
    }

    private static Map<String, String> namen(Map<KexProposalOption, String> vorschlag) {
        Map<String, String> namen = new LinkedHashMap<>();
        if (vorschlag != null) {
            vorschlag.forEach((k, v) -> namen.put(k.getDescription(), v));
        }
        return namen;
    }
}
