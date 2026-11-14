package ch.urinet.archiv.integration;

import com.icegreen.greenmail.Managers;
import com.icegreen.greenmail.imap.ImapHostManager;
import com.icegreen.greenmail.imap.ImapHostManagerImpl;
import com.icegreen.greenmail.smtp.SmtpManager;
import com.icegreen.greenmail.server.AbstractServer;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.store.StoredMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.user.UserManager;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GreenMail als IMAP-Server über TLS für die Szenarien des Mail-Eingangs (Spec 02, design.md «Testing Strategy»): einmal
 * je Testlauf auf {@code localhost} mit freiem Port, Zertifikat für {@code localhost} aus einer eigenen Test-CA. Das
 * Archiv vertraut nur dieser CA ({@code %test.archiv.mail.ca-datei}), mit Prüfung des Hostnamens. Jedes Szenario
 * erhält ein eigenes Postfach mit zufälligem Benutzer und Passwort; nur synthetische Nachrichten (Req 16.15).
 */
public final class TestPostfach {

    public static final String HOST = "localhost";
    static final Path VERZEICHNIS = Path.of(System.getProperty("user.dir"), "target", "greenmail");
    private static final SecureRandom ZUFALL = new SecureRandom();
    private static final Set<String> ABONNIEREN_FEHLER = ConcurrentHashMap.newKeySet();
    private static GreenMail server;
    private static ImapHostManager imapHostManager;

    private final GreenMailUser benutzer;
    private final String login;
    private final String passwort;

    private TestPostfach(GreenMailUser benutzer, String login, String passwort) {
        this.benutzer = benutzer;
        this.login = login;
        this.passwort = passwort;
    }

    /** Neues, leeres Postfach auf dem laufenden Server. */
    public static synchronized TestPostfach neu() throws Exception {
        if (server == null) {
            starten();
        }
        String kennung = HexFormat.of().formatHex(zufall(6));
        String login = "archiv-" + kennung + "@example.invalid";
        String passwort = Base64.getUrlEncoder().withoutPadding().encodeToString(zufall(18));
        return new TestPostfach(server.setUser(login, login, passwort), login, passwort);
    }

    public static int port() {
        return server.getImaps().getPort();
    }

    public String login() {
        return login;
    }

    public String passwort() {
        return passwort;
    }

    public void zustellen(jakarta.mail.internet.MimeMessage nachricht) {
        benutzer.deliver(nachricht);
    }

    /** Message-IDs der Nachrichten im Ordner; leer, wenn es ihn nicht gibt. */
    public List<String> ordner(String name) throws Exception {
        MailFolder ordner = server.getManagers().getImapHostManager().getFolder(benutzer, name);
        if (ordner == null) {
            return List.of();
        }
        return ordner.getMessages().stream().map(TestPostfach::messageId).toList();
    }

    public void ordnerAnlegen(String name) throws Exception {
        server.getManagers().getImapHostManager().createMailbox(benutzer, name);
    }

    public void abonnierenFehlschlagen(String name) {
        ABONNIEREN_FEHLER.add(benutzer.getLogin() + "|" + name);
    }

    public void abbestellen(String name) throws Exception {
        imapHostManager.unsubscribe(benutzer, name);
    }

    public boolean ordnerAbonniert(String name) throws Exception {
        return imapHostManager.listSubscribedMailboxes(benutzer, "*")
                .stream().anyMatch(ordner -> ordner.getFullName().equals(name)
                        || ordner.getFullName().endsWith("." + name));
    }

    private static String messageId(StoredMessage nachricht) {
        try {
            return nachricht.getMimeMessage().getMessageID();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void starten() throws Exception {
        Files.createDirectories(VERZEICHNIS);
        KeyPairGenerator erzeuger = KeyPairGenerator.getInstance("RSA");
        erzeuger.initialize(2048);
        KeyPair ca = erzeuger.generateKeyPair();
        KeyPair server = erzeuger.generateKeyPair();
        X509Certificate caZertifikat = zertifikat("CN=Test-CA Mail-Eingang", ca, "CN=Test-CA Mail-Eingang", ca, true);
        X509Certificate serverZertifikat = zertifikat("CN=localhost", server, "CN=Test-CA Mail-Eingang", ca, false);
        char[] kennwort = HexFormat.of().formatHex(zufall(12)).toCharArray();
        KeyStore schluessel = KeyStore.getInstance("PKCS12");
        schluessel.load(null, null);
        schluessel.setKeyEntry("greenmail", server.getPrivate(), kennwort,
                new Certificate[]{serverZertifikat, caZertifikat});
        Path p12 = VERZEICHNIS.resolve("server.p12");
        try (OutputStream aus = Files.newOutputStream(p12)) {
            schluessel.store(aus, kennwort);
        }
        Files.writeString(VERZEICHNIS.resolve("ca.pem"), "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(caZertifikat.getEncoded()) + "\n-----END CERTIFICATE-----\n");
        System.setProperty("greenmail.tls.keystore.file", p12.toString());
        System.setProperty("greenmail.tls.keystore.password", new String(kennwort));
        System.setProperty("greenmail.tls.key.password", new String(kennwort));
        TestPostfach.server = new TestGreenMail(new ServerSetup(0, HOST, ServerSetup.PROTOCOL_IMAPS));
        TestPostfach.server.start();
    }

    private static final class TestGreenMail extends GreenMail {
        TestGreenMail(ServerSetup setup) {
            super(setup);
        }

        @Override
        protected Map<String, AbstractServer> createServices(ServerSetup[] setups, Managers managers) {
            imapHostManager = new ImapHostManagerImpl(managers.getImapHostManager().getStore()) {
                @Override
                public void subscribe(GreenMailUser user, String mailboxName) throws FolderException {
                    if (ABONNIEREN_FEHLER.contains(user.getLogin() + "|" + mailboxName)) {
                        throw new FolderException("Synthetic subscription failure");
                    }
                    super.subscribe(user, mailboxName);
                }
            };
            ImapHostManager testImapHostManager = imapHostManager;
            Managers testManagers = new Managers() {
                @Override
                public ImapHostManager getImapHostManager() {
                    return testImapHostManager;
                }

                @Override
                public UserManager getUserManager() {
                    return managers.getUserManager();
                }

                @Override
                public SmtpManager getSmtpManager() {
                    return managers.getSmtpManager();
                }
            };
            return super.createServices(setups, testManagers);
        }
    }

    private static X509Certificate zertifikat(String name, KeyPair paar, String aussteller, KeyPair ausstellerPaar,
                                              boolean ca) throws Exception {
        Instant jetzt = Instant.now();
        var bauer = new JcaX509v3CertificateBuilder(new X500Name(aussteller), new BigInteger(64, ZUFALL),
                Date.from(jetzt.minus(Duration.ofDays(1))), Date.from(jetzt.plus(Duration.ofDays(2))),
                new X500Name(name), paar.getPublic());
        if (ca) {
            bauer.addExtension(Extension.basicConstraints, true, new org.bouncycastle.asn1.x509.BasicConstraints(true));
        } else {
            bauer.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[]{
                    new GeneralName(GeneralName.dNSName, HOST), new GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                    new GeneralName(GeneralName.iPAddress, "::1")}));
        }
        return new JcaX509CertificateConverter().getCertificate(bauer.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(ausstellerPaar.getPrivate())));
    }

    private static byte[] zufall(int laenge) {
        byte[] bytes = new byte[laenge];
        ZUFALL.nextBytes(bytes);
        return bytes;
    }
}
