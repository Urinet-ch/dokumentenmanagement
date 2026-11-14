package ch.urinet.archiv.integration.adapter.out.imap;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.integration.application.mail.PostfachNichtErreichbar;
import ch.urinet.archiv.integration.application.mail.PostfachPort;
import ch.urinet.archiv.integration.domain.mail.MailKopf;
import ch.urinet.archiv.integration.domain.mail.Postfach;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.StoreClosedException;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.SharedFileInputStream;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PostfachPort} über IMAP mit TLS (Angus Mail, Store {@code imaps}; Spec 02, Req 1.1, 1.4, 1.5). Das Zertifikat
 * des Servers wird mit Hostname geprüft, gegen die Zertifizierungsstellen des JDK oder, nur wenn
 * {@code archiv.mail.ca-datei} gesetzt ist, gegen die Zertifikate dieser PEM-Datei (Tests mit GreenMail). Die Ordner
 * {@code archiv.mail.ordner-archiviert} und {@code archiv.mail.ordner-quarantaene} legt das Archiv an, wenn sie fehlen
 * (bei Servern mit Präfix des persönlichen Namensraums, z. B. {@code INBOX.}, darunter). Verschoben wird mit
 * {@code MOVE} (RFC 6851), sonst mit Kopie, Löschmarke und {@code EXPUNGE} beim Schliessen. Je Abruf versucht das
 * Archiv höchstens {@code max} Nachrichten; eine Nachricht, die scheitert, bleibt im Posteingang und wird für
 * {@code archiv.mail.zurueckstellen} übersprungen, damit Nachrichten, die immer scheitern (zu gross, unlesbar), die
 * übrigen nicht blockieren.
 * <p>
 * Jede Nachricht liegt während der Verarbeitung als {@code .eml} mit ihren Anhängen in einem eigenen Verzeichnis unter
 * {@code archiv.mail.arbeitsverzeichnis} (Rechte 700) und wird danach gelöscht; ganz im Heap liegt sie nie. Das Log
 * nennt weder Host, Benutzer noch Passwort, von Fehlern nur die Klassen (Req 16.7).
 */
@ApplicationScoped
@Typed(ImapPostfach.class)
public class ImapPostfach implements PostfachPort {

    private static final Logger LOG = Logger.getLogger(ImapPostfach.class);

    private final Duration zeitgrenze;
    private final String archiviert;
    private final String quarantaene;
    private final Optional<Path> caDatei;
    private final Path arbeitsverzeichnis;
    private final long maxGroesse;
    private final Duration zurueckstellen;
    private final long eingebettetAb;
    private final Clock uhr;
    /** Gescheiterte Nachrichten (Postfach, UIDVALIDITY, UID) → frühester nächster Versuch. */
    private final Map<String, Instant> zurueckgestellt = new ConcurrentHashMap<>();

    @Inject
    public ImapPostfach(@ConfigProperty(name = "archiv.mail.zeitgrenze") Duration zeitgrenze,
                        @ConfigProperty(name = "archiv.mail.ordner-archiviert") String archiviert,
                        @ConfigProperty(name = "archiv.mail.ordner-quarantaene") String quarantaene,
                        @ConfigProperty(name = "archiv.mail.ca-datei") Optional<Path> caDatei,
                        @ConfigProperty(name = "archiv.mail.arbeitsverzeichnis") Path arbeitsverzeichnis,
                        @ConfigProperty(name = "archiv.mail.max-groesse") MemorySize maxGroesse,
                        @ConfigProperty(name = "archiv.mail.zurueckstellen") Duration zurueckstellen,
                        @ConfigProperty(name = "archiv.mail.eingebettet-ab") MemorySize eingebettetAb) {
        this(zeitgrenze, archiviert, quarantaene, caDatei, arbeitsverzeichnis, maxGroesse.asLongValue(), zurueckstellen,
                eingebettetAb.asLongValue(), Clock.systemUTC());
    }

    ImapPostfach(Duration zeitgrenze, String archiviert, String quarantaene, Optional<Path> caDatei,
                 Path arbeitsverzeichnis, long maxGroesse, Duration zurueckstellen, long eingebettetAb, Clock uhr) {
        this.zeitgrenze = zeitgrenze;
        this.archiviert = archiviert;
        this.quarantaene = quarantaene;
        this.caDatei = caDatei;
        this.arbeitsverzeichnis = arbeitsverzeichnis;
        this.maxGroesse = maxGroesse;
        this.zurueckstellen = zurueckstellen;
        this.eingebettetAb = eingebettetAb;
        this.uhr = uhr;
    }

    @Override
    public Abruf abrufen(Postfach postfach, String passwort, int max, Verarbeiter verarbeiter) {
        Session session;
        try {
            session = Session.getInstance(eigenschaften());
        } catch (RuntimeException e) {
            throw new PostfachNichtErreichbar("TLS-Einstellungen", e);
        }
        Store store;
        try {
            store = session.getStore("imaps");
            store.connect(postfach.host(), postfach.port(), postfach.benutzer(), passwort);
        } catch (MessagingException e) {
            throw new PostfachNichtErreichbar("Anmeldung", e);
        }
        try {
            Folder eingang = store.getFolder("INBOX");
            eingang.open(Folder.READ_WRITE);
            Folder zielArchiviert = ordner(store, archiviert);
            Folder zielQuarantaene = ordner(store, quarantaene);
            Message[] nachrichten = eingang.getMessages();
            if (nachrichten.length == 0) {
                eingang.close(false);
                return new Abruf(0, 0);
            }
            FetchProfile profil = new FetchProfile();
            profil.add(FetchProfile.Item.SIZE);
            profil.add(UIDFolder.FetchProfileItem.UID);
            eingang.fetch(nachrichten, profil);
            String ort = postfach.host() + "|" + postfach.port() + "|" + postfach.benutzer() + "|"
                    + ((UIDFolder) eingang).getUIDValidity() + "|";
            Instant jetzt = uhr.instant();
            int verarbeitet = 0;
            int gescheitert = 0;
            int wartend = 0;
            Set<String> vorhanden = new HashSet<>();
            for (Message nachricht : nachrichten) {
                vorhanden.add(ort + ((UIDFolder) eingang).getUID(nachricht));
            }
            // Einträge zu Nachrichten, die nicht mehr im Posteingang liegen, fallen weg
            zurueckgestellt.keySet().removeIf(k -> k.startsWith(ort) && !vorhanden.contains(k));
            for (Message nachricht : nachrichten) {
                if (verarbeitet + gescheitert >= max) {
                    break;
                }
                String schluessel = ort + ((UIDFolder) eingang).getUID(nachricht);
                Instant naechster = zurueckgestellt.get(schluessel);
                if (naechster != null && jetzt.isBefore(naechster)) {
                    // eine Nachricht, die zuletzt scheiterte, belegt das Fenster nicht; nach der Wartezeit wieder
                    wartend++;
                    continue;
                }
                Ziel ziel;
                try {
                    ziel = verarbeiten(session, nachricht, verarbeiter);
                    zurueckgestellt.remove(schluessel);
                } catch (FolderClosedException | StoreClosedException e) {
                    throw new PostfachNichtErreichbar("Verbindung", e);
                } catch (Exception e) {
                    // bleibt im Posteingang; der nächste Versuch nach archiv.mail.zurueckstellen
                    gescheitert++;
                    zurueckgestellt.put(schluessel, jetzt.plus(zurueckstellen));
                    LOG.warnf("Mail-Eingang: Nachricht nicht verarbeitet, bleibt im Posteingang und wird zurückgestellt, "
                            + "ursache=%s", Ursachen.ketteMitMeldung(e));
                    continue;
                }
                try {
                    // erfasst und festgehalten; scheitert nur das Verschieben, holt es der nächste Takt nach, ohne
                    // erneut zu erfassen (mail_eingang, Req 1.5)
                    verschieben(store, eingang, nachricht, ziel == Ziel.QUARANTAENE ? zielQuarantaene : zielArchiviert);
                    verarbeitet++;
                } catch (FolderClosedException | StoreClosedException e) {
                    throw new PostfachNichtErreichbar("Verbindung", e);
                } catch (MessagingException e) {
                    gescheitert++;
                    LOG.warnf("Mail-Eingang: Nachricht erfasst, aber nicht verschoben, der nächste Takt holt es nach, "
                            + "ursache=%s", Ursachen.ketteMitMeldung(e));
                }
            }
            if (wartend > 0) {
                LOG.infof("Mail-Eingang: %d zurückgestellte Nachricht(en) im Posteingang", wartend);
            }
            eingang.close(true);
            return new Abruf(verarbeitet, gescheitert);
        } catch (MessagingException e) {
            throw new PostfachNichtErreichbar("Ordner", e);
        } finally {
            schliessen(store);
        }
    }

    private static void schliessen(Store store) {
        try {
            store.close();
        } catch (MessagingException e) {
            LOG.debugf("Mail-Eingang: Verbindung nicht sauber geschlossen (%s)", e.getClass().getName());
        }
    }

    private Ziel verarbeiten(Session session, Message nachricht, Verarbeiter verarbeiter) throws Exception {
        if (nachricht.getSize() > maxGroesse) {
            throw new IllegalStateException("Nachricht grösser als archiv.mail.max-groesse");
        }
        Path verzeichnis = verzeichnisAnlegen();
        try {
            Path eml = verzeichnis.resolve("nachricht.eml");
            try (OutputStream aus = Files.newOutputStream(eml)) {
                nachricht.writeTo(aus);
            }
            try (SharedFileInputStream ein = new SharedFileInputStream(eml.toFile())) {
                MimeMessage kopie = new MimeMessage(session, ein);
                MailTeile teile = MailTeile.lesen(kopie, verzeichnis, eingebettetAb);
                Optional<Date> datum = Optional.ofNullable(kopie.getSentDate())
                        .or(() -> Optional.ofNullable(empfangen(nachricht)));
                return verarbeiter.verarbeiten(new Nachricht(messageId(kopie, eml), MailTeile.absender(kopie),
                        Optional.ofNullable(MailTeile.betreff(kopie)), datum.map(Date::toInstant), eml,
                        teile.anhaenge(), teile.text()));
            }
        } finally {
            loeschen(verzeichnis);
        }
    }

    private static Date empfangen(Message nachricht) {
        try {
            return nachricht.getReceivedDate();
        } catch (MessagingException e) {
            return null;
        }
    }

    /**
     * Message-ID aus dem Kopf, bereinigt ({@link MailKopf#messageId}); ohne sie {@code sha256:<Prüfsumme der Nachricht>}
     * (Req 1.5).
     */
    static String messageId(MimeMessage nachricht, Path eml) throws MessagingException, IOException {
        Optional<String> id = MailKopf.messageId(nachricht.getMessageID());
        if (id.isPresent()) {
            return id.get();
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream ein = new DigestInputStream(Files.newInputStream(eml), digest)) {
            ein.transferTo(OutputStream.nullOutputStream());
        }
        return MailKopf.PRAEFIX_HASH + HexFormat.of().formatHex(digest.digest());
    }

    private void verschieben(Store store, Folder eingang, Message nachricht, Folder ziel) throws MessagingException {
        Message[] eine = {nachricht};
        if (eingang instanceof IMAPFolder imap && store instanceof IMAPStore imapStore
                && imapStore.hasCapability("MOVE")) {
            imap.moveMessages(eine, ziel);
        } else {
            eingang.copyMessages(eine, ziel);
            nachricht.setFlag(Flags.Flag.DELETED, true);
        }
    }

    /** Ordner {@code name}, sonst unter dem persönlichen Namensraum; fehlt er, legt das Archiv ihn an. */
    private static Folder ordner(Store store, String name) throws MessagingException {
        Folder ordner = store.getFolder(name);
        if (ordner.exists()) {
            return ordner;
        }
        Folder[] namensraum = store.getPersonalNamespaces();
        if (namensraum.length > 0 && !namensraum[0].getFullName().isEmpty()) {
            Folder darunter = store.getFolder(namensraum[0].getFullName() + namensraum[0].getSeparator() + name);
            if (darunter.exists()) {
                return darunter;
            }
            ordner = darunter;
        }
        if (!ordner.create(Folder.HOLDS_MESSAGES)) {
            throw new MessagingException("Ordner nicht angelegt");
        }
        LOG.infof("Mail-Eingang: IMAP-Ordner %s angelegt", name);
        return ordner;
    }

    private Properties eigenschaften() {
        String ms = Long.toString(zeitgrenze.toMillis());
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.ssl.checkserveridentity", "true");
        p.put("mail.imaps.ssl.protocols", "TLSv1.3 TLSv1.2");
        p.put("mail.imaps.connectiontimeout", ms);
        p.put("mail.imaps.timeout", ms);
        p.put("mail.imaps.writetimeout", ms);
        caDatei.ifPresent(datei -> p.put("mail.imaps.ssl.socketFactory", socketFactory(datei)));
        return p;
    }

    /** TLS nur gegen die Zertifikate der PEM-Datei (statt der Zertifizierungsstellen des JDK). */
    static SSLSocketFactory socketFactory(Path pem) {
        try (InputStream ein = Files.newInputStream(pem)) {
            KeyStore vertrauen = KeyStore.getInstance(KeyStore.getDefaultType());
            vertrauen.load(null, null);
            int nr = 0;
            for (Certificate zertifikat : CertificateFactory.getInstance("X.509").generateCertificates(ein)) {
                vertrauen.setCertificateEntry("ca-" + nr++, zertifikat);
            }
            if (nr == 0) {
                throw new IllegalStateException("archiv.mail.ca-datei enthält kein Zertifikat");
            }
            TrustManagerFactory pruefung = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            pruefung.init(vertrauen);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, pruefung.getTrustManagers(), null);
            return tls.getSocketFactory();
        } catch (IOException e) {
            throw new UncheckedIOException("archiv.mail.ca-datei nicht lesbar", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("archiv.mail.ca-datei ungültig", e);
        }
    }

    private Path verzeichnisAnlegen() throws IOException {
        Files.createDirectories(arbeitsverzeichnis);
        try {
            return Files.createTempDirectory(arbeitsverzeichnis, "mail-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            return Files.createTempDirectory(arbeitsverzeichnis, "mail-");
        }
    }

    private static void loeschen(Path verzeichnis) {
        try {
            Files.walkFileTree(verzeichnis, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path datei, BasicFileAttributes attribute) throws IOException {
                    Files.deleteIfExists(datei);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path ordner, IOException fehler) throws IOException {
                    Files.deleteIfExists(ordner);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException e) {
            LOG.warnf("Arbeitsverzeichnis des Mail-Eingangs nicht gelöscht: %s (%s)", verzeichnis.getFileName(),
                    e.getClass().getName());
        }
    }
}
