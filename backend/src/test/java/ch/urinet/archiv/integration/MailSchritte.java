package ch.urinet.archiv.integration;

import ch.urinet.archiv.dokument.Testdateien;
import ch.urinet.archiv.ablage.S3Stoerung;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.integration.application.mail.MailEingang;
import ch.urinet.archiv.integration.application.mail.MailEinrichten;
import ch.urinet.archiv.integration.domain.mail.MailKopf;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import jakarta.activation.DataHandler;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Spec 02, Req 1.1–1.6 (Aufgabe 6.1): Postfach in GreenMail ({@link TestPostfach}), Abruf über
 * {@link MailEingang} wie im Takt, eingerichtet über {@link MailEinrichten} wie mit dem Kommando {@code mail einrichten}.
 * Nur synthetische Nachrichten und Dateien (Req 16.15).
 */
public class MailSchritte {

    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    @Inject
    MailEinrichten einrichten;

    @Inject
    MailEingang eingang;

    @Inject
    VerarbeitungTestHilfe verarbeitung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final Map<String, TestPostfach> postfaecher = new HashMap<>();
    private final Map<String, List<String>> absender = new HashMap<>();
    /** Message-IDs der zugestellten Nachrichten in der Reihenfolge der Zustellung. */
    private final List<String> nachrichten = new ArrayList<>();
    /** Anzahl Dokumente mit Quelle EMAIL je Mandant vor dem Szenario. */
    private final Map<String, Long> vorher = new HashMap<>();
    private byte[] letztesPdf;
    private byte[] logo;
    private MimeMessage letzteNachricht;
    private LogMitschnitt log;

    @After("@spec-02")
    public void aufraeumen() {
        S3Stoerung.aus();
        if (log != null) {
            log.close();
        }
    }

    @Angenommen("für «{word}» ist ein Postfach mit den zugelassenen Absendern «{}» und «{}» eingerichtet")
    public void eingerichtet(String mandant, String a, String b) throws Exception {
        absender.put(mandant, List.of(a, b));
        postfachEinrichten(mandant, true);
    }

    @Angenommen("für «{word}» ist kein Postfach eingerichtet")
    public void keinPostfach(String mandant) throws SQLException {
        ausfuehren("delete from " + schema(mandant) + ".integration_mail");
        vorher.put(mandant, emailDokumente(mandant));
    }

    @Angenommen("das Postfach von «{word}» hat weder «Archiviert» noch «Quarantaene»")
    public void ohneOrdner(String mandant) throws Exception {
        postfachEinrichten(mandant, false);
    }

    @Angenommen("das Passwort des Postfachs von «{word}» ist falsch eingerichtet")
    public void falschesPasswort(String mandant) {
        TestPostfach postfach = postfaecher.get(mandant);
        einrichten.einrichten(mandant, TestPostfach.HOST, TestPostfach.port(), postfach.login(),
                postfach.passwort() + "-falsch", absender.get(mandant));
    }

    @Angenommen("das Log des Mail-Eingangs wird mitgeschnitten")
    public void logMitschneiden() {
        log = LogMitschnitt.starten();
    }

    @Angenommen("im Postfach liegt eine Mail von «{}» mit dem Betreff «{}» und einem PDF «{}»")
    public void mailMitPdf(String von, String betreff, String name) throws Exception {
        letztesPdf = Testdateien.pdf();
        zustellen(nachricht(von, betreff, null, "Siehe Anhang.", List.of(new Teil(name, letztesPdf, "application/pdf"))),
                neueId());
    }

    @Angenommen("im Postfach liegt eine Mail von «{}» vom «{}» mit dem Betreff «{}» und diesen Anhängen:")
    public void mailMitAnhaengen(String von, String datum, String betreff, DataTable tabelle) throws Exception {
        List<Teil> teile = new ArrayList<>();
        for (Map<String, String> zeile : tabelle.asMaps()) {
            String typ = zeile.get("typ");
            String mime = switch (typ) {
                case "pdf" -> "application/pdf";
                case "png" -> "image/png";
                default -> "text/plain";
            };
            teile.add(new Teil(zeile.get("name"), Testdateien.erzeugen(typ), mime));
        }
        Date gesendet = Date.from(LocalDateTime.parse(datum, DATUM).atZone(ZUERICH).toInstant());
        zustellen(nachricht(von, betreff, gesendet, "Belege im Anhang.", teile), neueId());
    }

    @Angenommen("im Postfach liegt eine Mail von «{}» mit dem Betreff «{}» und dem Text «{}» ohne Anhang")
    public void mailOhneAnhang(String von, String betreff, String text) throws Exception {
        zustellen(nachricht(von, betreff, null, "Guten Tag\n\n" + text + "\n\nFreundliche Grüsse", List.of()),
                neueId());
    }

    @Angenommen("dieselbe Mail liegt mit derselben Message-ID und einem anderen PDF noch einmal im Posteingang")
    public void dieselbeMessageId() throws Exception {
        zustellen(nachricht("inhaber@example.invalid", "Zweimal gesendet", null, "Noch einmal.",
                List.of(new Teil("beleg.pdf", Testdateien.pdf(), "application/pdf"))), nachrichten.getLast());
    }

    @Angenommen("im Postfach liegt eine zweite Mail von «{}» mit demselben PDF")
    public void zweiteMitDemselbenPdf(String von) throws Exception {
        zustellen(nachricht(von, "Kopie", null, "Noch einmal derselbe Beleg.",
                List.of(new Teil("beleg-kopie.pdf", letztesPdf, "application/pdf"))), neueId());
    }

    @Angenommen("der Mail-Eingang hat das Postfach von «{word}» abgerufen")
    @Wenn("der Mail-Eingang das Postfach von «{word}» abruft")
    public void abrufen(String mandant) throws Exception {
        // eigener Thread wie im Takt des Schedulers: ohne Request-Kontext und Anmeldung des Testlaufs
        CompletableFuture.runAsync(() -> eingang.abrufen(new MandantCode(mandant))).get(2, TimeUnit.MINUTES);
    }

    @Wenn("der Mail-Eingang das Postfach von «{word}» zweimal abruft")
    public void zweimalAbrufen(String mandant) throws Exception {
        abrufen(mandant);
        abrufen(mandant);
    }

    @Dann("liegt die Mail im Ordner «{word}» und der Posteingang ist leer")
    public void imOrdner(String ordner) throws Exception {
        TestPostfach postfach = postfaecher.get("urinet");
        assertThat(postfach.ordner(ordner)).contains(nachrichten.getLast());
        assertThat(postfach.ordner("INBOX")).isEmpty();
    }

    @Dann("liegen beide Nachrichten im Ordner «{word}» und der Posteingang ist leer")
    public void beideImOrdner(String ordner) throws Exception {
        TestPostfach postfach = postfaecher.get("urinet");
        assertThat(postfach.ordner(ordner)).hasSize(2).containsOnly(nachrichten.getLast());
        assertThat(postfach.ordner("INBOX")).isEmpty();
    }

    @Dann("gibt es im Postfach die Ordner «{word}» und «{word}» mit je einer Mail")
    public void ordnerAngelegt(String a, String b) throws Exception {
        TestPostfach postfach = postfaecher.get("urinet");
        assertThat(postfach.ordner(a)).containsExactly(nachrichten.get(0));
        assertThat(postfach.ordner(b)).containsExactly(nachrichten.get(1));
        assertThat(postfach.ordner("INBOX")).isEmpty();
    }

    @Dann("das Passwort steht in «integration_mail» von «{word}» nur verschlüsselt")
    public void nurVerschluesselt(String mandant) throws SQLException {
        byte[] gespeichert;
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select passwort_verschluesselt from " + schema(mandant)
                        + ".integration_mail")) {
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                gespeichert = zeile.getBytes(1);
            }
        }
        String passwort = postfaecher.get(mandant).passwort();
        assertThat(gespeichert.length).isGreaterThan(passwort.length());
        assertThat(new String(gespeichert, StandardCharsets.ISO_8859_1)).doesNotContain(passwort);
    }

    @Dann("enthält das Log weder Benutzer noch Passwort des Postfachs")
    public void logOhneZugang() {
        TestPostfach postfach = postfaecher.get("urinet");
        String text = log.volltext();
        assertThat(text).contains("Mail-Eingang").doesNotContain(postfach.passwort())
                .doesNotContain(postfach.login());
    }

    @Dann("zählt «integration_mail» von «{word}» {int} gescheiterten Abruf in Folge")
    public void fehlschlaege(String mandant, int anzahl) throws SQLException {
        assertThat(wert("select fehler_in_folge from " + schema(mandant) + ".integration_mail")).isEqualTo(anzahl);
        assertThat(log.volltext()).contains("Abruf für Mandant " + mandant + " gescheitert (" + anzahl
                + " in Folge), stelle=Anmeldung, ursache=jakarta.mail.AuthenticationFailedException");
    }

    @Dann("meldet das Log genau einmal, dass der Mail-Eingang für «{word}» ruht")
    public void einmalRuht(String mandant) {
        assertThat(mailEintraege().stream().map(log::nachricht)
                .filter(n -> n.startsWith("Mail-Eingang für Mandant " + mandant + " ruht"))).hasSize(1);
    }

    @Dann("das Log enthält zum Mail-Eingang keine Warnung und keinen Fehler")
    public void keineWarnung() {
        assertThat(mailEintraege()).noneMatch(e -> e.getLevel().intValue() >= Level.WARNING.intValue());
    }

    @Dann("hat das Archiv im Mandanten «{word}» zur Mail diese Dokumente mit Quelle «{word}» angelegt:")
    public void dokumente(String mandant, String quelle, DataTable tabelle) throws SQLException {
        List<UUID> ids = dokumentIds(mandant, nachrichten.getLast());
        List<Map<String, String>> ist = new ArrayList<>();
        for (UUID id : ids) {
            Map<String, Object> dok = zeile("select quelle, dateiname, hinweis from " + schema(mandant)
                    + ".dokument where id = ?", id);
            assertThat(dok.get("quelle")).isEqualTo(quelle);
            ist.add(Map.of("dateiname", String.valueOf(dok.get("dateiname")), "hinweis",
                    String.valueOf(dok.get("hinweis"))));
        }
        assertThat(ist).containsExactlyInAnyOrderElementsOf(tabelle.asMaps());
        assertThat(emailDokumente(mandant) - vorher.get(mandant)).isEqualTo(ids.size());
    }

    @Dann("jedes dieser Dokumente steht unbestätigt als «{word}» in «{word}» mit einem Auftrag «{word}»")
    public void unbestaetigt(String vertraulichkeit, String status, String auftrag) throws SQLException {
        for (UUID id : dokumentIds("urinet", nachrichten.getLast())) {
            Map<String, Object> dok = zeile("select vertraulichkeit, status, bestaetigt, erstellt_von from "
                    + schema("urinet") + ".dokument where id = ?", id);
            assertThat(dok).containsEntry("vertraulichkeit", vertraulichkeit).containsEntry("status", status)
                    .containsEntry("erstellt_von", "mail-eingang");
            assertThat(dok.get("bestaetigt")).isNull();
            assertThat(verarbeitung.auftragStatus(auftrag, id)).containsExactly("OFFEN");
        }
    }

    @Dann("«mail_eingang» von «{word}» führt die Mail als «{word}» mit diesen Dokumenten")
    public void eingangMitDokumenten(String mandant, String status) throws SQLException {
        assertThat(status(mandant, nachrichten.getLast())).isEqualTo(status);
        assertThat(dokumentIds(mandant, nachrichten.getLast())).hasSize(2);
    }

    @Dann("«mail_eingang» von «{word}» führt die Mail als «{word}» ohne Dokument")
    public void eingangOhneDokument(String mandant, String status) throws SQLException {
        assertThat(status(mandant, nachrichten.getLast())).isEqualTo(status);
        assertThat(dokumentIds(mandant, nachrichten.getLast())).isEmpty();
    }

    @Dann("«mail_eingang» von «{word}» führt die zweite Mail als «{word}» ohne Dokument")
    public void zweiteOhneDokument(String mandant, String status) throws SQLException {
        eingangOhneDokument(mandant, status);
    }

    @Dann("im Mandanten «{word}» ist zur Mail nichts entstanden")
    public void nichtsImMandanten(String mandant) throws SQLException {
        assertThat(emailDokumente(mandant)).isEqualTo(vorher.get(mandant));
        assertThat(wert("select count(*) from " + schema(mandant) + ".mail_eingang where message_id = ?",
                gespeichert(nachrichten.getLast()))).isZero();
    }

    @Dann("hat das Archiv im Mandanten «{word}» zur Mail kein Dokument angelegt")
    public void keinDokument(String mandant) throws SQLException {
        assertThat(emailDokumente(mandant)).isEqualTo(vorher.get(mandant));
    }

    @Dann("hat das Archiv im Mandanten «{word}» zur zweiten Mail kein Dokument angelegt, auch keine Korrespondenz")
    public void zweiteOhneDokumentAngelegt(String mandant) throws SQLException {
        assertThat(emailDokumente(mandant)).isEqualTo(vorher.get(mandant) + 1);
    }

    @Dann("hat das Archiv im Mandanten «{word}» zur Mail genau ein Dokument angelegt")
    public void genauEines(String mandant) throws SQLException {
        assertThat(dokumentIds(mandant, nachrichten.getLast())).hasSize(1);
        assertThat(emailDokumente(mandant)).isEqualTo(vorher.get(mandant) + 1);
        // Stand für die nächste Mail desselben Szenarios
        vorher.put(mandant, vorher.get(mandant) + 1);
    }

    @Dann("hat das Archiv im Mandanten «{word}» zur Mail genau ein Dokument der Art «{word}» mit Quelle «{word}» angelegt")
    public void korrespondenz(String mandant, String art, String quelle) throws SQLException {
        genauEines(mandant);
        assertThat(zeile("select mime from " + schema(mandant) + ".dokument_datei where dokument_id = ? and nr = 1",
                dokument(mandant))).containsEntry("mime", "message/rfc822");
        Map<String, Object> dok = zeile("select art, quelle, dateiname from " + schema(mandant)
                + ".dokument where id = ?", dokument(mandant));
        assertThat(dok).containsEntry("art", art).containsEntry("quelle", quelle);
        assertThat(dok.get("dateiname")).isNull();
    }

    @Dann("das Dokument hat die Nachricht als Original {int} «{}» und ihren Text als Original {int} «{}»")
    public void originale(int nrEml, String mimeEml, int nrPdf, String mimePdf) throws SQLException {
        UUID id = dokument("urinet");
        Map<String, String[]> dateien = verarbeitung.dateien("urinet", id);
        assertThat(dateien.keySet()).containsExactlyInAnyOrder("ORIGINAL-" + nrEml, "ORIGINAL-" + nrPdf);
        assertThat(zeile("select mime, s3_schluessel from " + schema("urinet") + ".dokument_datei where "
                + "dokument_id = ? and nr = " + nrEml, id)).containsEntry("mime", mimeEml)
                .hasEntrySatisfying("s3_schluessel", s -> assertThat((String) s).endsWith(nrEml + "-original.eml"));
        assertThat(zeile("select mime from " + schema("urinet") + ".dokument_datei where dokument_id = ? and nr = "
                + nrPdf, id)).containsEntry("mime", mimePdf);
    }

    @Dann("steht das Dokument der Mail unbestätigt im «{word}» mit PDF\\/A und dem Text «{}» im Volltext")
    public void imEingangskorb(String status, String text) throws SQLException {
        UUID id = dokument("urinet");
        Map<String, Object> dok = verarbeitung.dokument("urinet", id);
        assertThat(dok).containsEntry("status", status).containsEntry("pdfa_fehlt", false);
        assertThat(dok.get("sha256_pdfa")).isNotNull();
        assertThat((String) dok.get("volltext")).contains(text);
        assertThat(zeile("select bestaetigt from " + schema("urinet") + ".dokument where id = ?", id)
                .get("bestaetigt")).isNull();
    }

    @Angenommen("im Postfach liegt eine HTML-Mail von «{}» mit dem Betreff «{}» und einem eingebetteten Logo")
    public void mailMitLogo(String von, String betreff) throws Exception {
        logo = Testdateien.png();
        zustellen(htmlMitLogo(von, betreff), neueId());
    }

    @Angenommen("im Postfach liegt eine zweite HTML-Mail von «{}» mit demselben eingebetteten Logo")
    public void zweiteHtmlMitLogo(String von) throws Exception {
        zustellen(htmlMitLogo(von, "Nachfrage"), neueId());
    }

    @Angenommen("im Postfach liegt eine Mail von «{}» mit dem Betreff «{}» und einem abgeschnittenen PDF «{}»")
    public void mitDefektemPdf(String von, String betreff, String name) throws Exception {
        zustellen(nachricht(von, betreff, null, "Siehe Anhang.", List.of(new Teil(name, Testdateien.abgeschnitten(),
                "application/pdf"))), neueId());
    }

    @Angenommen("im Postfach liegt eine Mail von «{}» mit Steuerzeichen im Betreff und einer Message-ID aus {int} Zeichen")
    public void kopfMitSteuerzeichen(String von, int laenge) throws Exception {
        MimeMessage nachricht = nachricht(von, "Platzhalter", null, "Text", List.of());
        nachricht.setHeader("Subject", "=?UTF-8?Q?Rechnung=00X=07Y?=");
        String domain = "@example.invalid>";
        zustellen(nachricht, "<" + "x".repeat(laenge - domain.length() - 1) + domain);
    }

    @Angenommen("für «{word}» ist «{}» zusätzlich zugelassen")
    public void zusaetzlichZugelassen(String mandant, String adresse) {
        List<String> liste = new ArrayList<>(absender.get(mandant));
        liste.add(adresse);
        absender.put(mandant, liste);
        TestPostfach postfach = postfaecher.get(mandant);
        einrichten.einrichten(mandant, TestPostfach.HOST, TestPostfach.port(), postfach.login(), postfach.passwort(),
                liste);
    }

    @Angenommen("die Mail liegt wieder im Posteingang")
    public void wiederImPosteingang() {
        postfaecher.get("urinet").zustellen(letzteNachricht);
    }

    @Angenommen("der Object Storage nimmt keine Datei mehr an")
    public void ablageFaelltAus() {
        S3Stoerung.ab(0, S3Stoerung.Art.NETZ);
    }

    @Angenommen("der Object Storage ist wieder erreichbar")
    public void ablageWieder() {
        S3Stoerung.aus();
    }

    @Dann("führt «mail_eingang» von «{word}» die Mail nicht, zur Mail gibt es kein Dokument, und sie liegt noch im Posteingang")
    public void nichtsEingetragen(String mandant) throws Exception {
        assertThat(wert("select count(*) from " + schema(mandant) + ".mail_eingang where message_id = ?",
                gespeichert(nachrichten.getLast()))).isZero();
        assertThat(emailDokumente(mandant)).isEqualTo(vorher.get(mandant));
        assertThat(postfaecher.get(mandant).ordner("INBOX")).containsExactly(nachrichten.getLast());
    }

    @Dann("«mail_eingang» von «{word}» führt die Mail als «{word}» mit diesem Dokument")
    public void eingangMitEinemDokument(String mandant, String status) throws SQLException {
        assertThat(status(mandant, nachrichten.getLast())).isEqualTo(status);
        assertThat(dokumentIds(mandant, nachrichten.getLast())).hasSize(1);
    }

    @Dann("«mail_eingang» von «{word}» führt die Mail unter dem Hash ihrer Message-ID als «{word}» mit bereinigtem Betreff")
    public void bereinigtGespeichert(String mandant, String status) throws SQLException {
        String id = gespeichert(nachrichten.getLast());
        assertThat(id).startsWith(MailKopf.PRAEFIX_HASH);
        Map<String, Object> zeile = zeile("select status, betreff from " + schema(mandant)
                + ".mail_eingang where message_id = ?", id);
        assertThat(zeile).containsEntry("status", status).containsEntry("betreff", "Rechnung X Y");
    }

    @Dann("das Log meldet zur Mail kein neues Dokument")
    public void logOhneNeuesDokument() {
        assertThat(mailEintraege().stream().map(log::nachricht))
                .anyMatch(n -> n.startsWith("Mail ohne neues Dokument: mandant=urinet"));
    }

    private MimeMessage htmlMitLogo(String von, String betreff) throws Exception {
        MimeMessage nachricht = new MimeMessage(Session.getInstance(new Properties()));
        nachricht.setFrom(new InternetAddress(von));
        nachricht.setRecipients(jakarta.mail.Message.RecipientType.TO, "archiv@example.invalid");
        nachricht.setSubject(betreff, "UTF-8");
        nachricht.setSentDate(new Date());
        MimeMultipart alternativ = new MimeMultipart("alternative");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Guten Tag, bitte um eine Offerte.", "UTF-8");
        MimeBodyPart html = new MimeBodyPart();
        html.setText("<p>Guten Tag, bitte um eine Offerte.</p><img src=\"cid:logo\">", "UTF-8", "html");
        alternativ.addBodyPart(text);
        alternativ.addBodyPart(html);
        MimeBodyPart huelle = new MimeBodyPart();
        huelle.setContent(alternativ);
        MimeBodyPart bild = new MimeBodyPart();
        bild.setDataHandler(new DataHandler(new ByteArrayDataSource(logo, "image/png")));
        bild.setFileName("image001.png");
        bild.setDisposition(jakarta.mail.Part.INLINE);
        bild.setContentID("<logo>");
        MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(huelle);
        related.addBodyPart(bild);
        nachricht.setContent(related);
        return nachricht;
    }

    private void postfachEinrichten(String mandant, boolean mitOrdnern) throws Exception {
        TestPostfach postfach = TestPostfach.neu();
        if (mitOrdnern) {
            postfach.ordnerAnlegen("Archiviert");
            postfach.ordnerAnlegen("Quarantaene");
        }
        postfaecher.put(mandant, postfach);
        einrichten.einrichten(mandant, TestPostfach.HOST, TestPostfach.port(), postfach.login(), postfach.passwort(),
                absender.get(mandant));
        vorher.put(mandant, emailDokumente(mandant));
    }

    private void zustellen(MimeMessage nachricht, String messageId) throws Exception {
        nachricht.saveChanges();
        nachricht.setHeader("Message-ID", messageId);
        postfaecher.get("urinet").zustellen(nachricht);
        nachrichten.add(messageId);
        letzteNachricht = nachricht;
    }

    /** So, wie das Archiv die Message-ID in {@code mail_eingang} speichert. */
    private static String gespeichert(String messageId) {
        return MailKopf.messageId(messageId).orElseThrow();
    }

    private static String neueId() {
        return "<" + UUID.randomUUID() + "@test.example.invalid>";
    }

    private static MimeMessage nachricht(String von, String betreff, Date gesendet, String text, List<Teil> teile)
            throws Exception {
        MimeMessage nachricht = new MimeMessage(Session.getInstance(new Properties()));
        nachricht.setFrom(new InternetAddress(von));
        nachricht.setRecipients(jakarta.mail.Message.RecipientType.TO, "archiv@example.invalid");
        nachricht.setSubject(betreff, "UTF-8");
        nachricht.setSentDate(gesendet != null ? gesendet : new Date());
        if (teile.isEmpty()) {
            nachricht.setText(text, "UTF-8");
            return nachricht;
        }
        MimeMultipart inhalt = new MimeMultipart();
        MimeBodyPart textteil = new MimeBodyPart();
        textteil.setText(text, "UTF-8");
        inhalt.addBodyPart(textteil);
        for (Teil teil : teile) {
            MimeBodyPart anhang = new MimeBodyPart();
            anhang.setDataHandler(new DataHandler(new ByteArrayDataSource(teil.inhalt(), teil.mime())));
            anhang.setFileName(teil.name());
            anhang.setDisposition(jakarta.mail.Part.ATTACHMENT);
            inhalt.addBodyPart(anhang);
        }
        nachricht.setContent(inhalt);
        return nachricht;
    }

    private List<LogRecord> mailEintraege() {
        return log.eintraege().stream()
                .filter(e -> e.getLoggerName() != null && e.getLoggerName().startsWith("ch.urinet.archiv.integration"))
                .toList();
    }

    private UUID dokument(String mandant) throws SQLException {
        List<UUID> ids = dokumentIds(mandant, nachrichten.getLast());
        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    private List<UUID> dokumentIds(String mandant, String messageId) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select dokument_ids from " + schema(mandant)
                        + ".mail_eingang where message_id = ?")) {
            abfrage.setString(1, gespeichert(messageId));
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    return List.of();
                }
                Array ids = zeile.getArray(1);
                return Arrays.asList((UUID[]) ids.getArray());
            }
        }
    }

    private String status(String mandant, String messageId) throws SQLException {
        return (String) zeile("select status from " + schema(mandant) + ".mail_eingang where message_id = ?",
                gespeichert(messageId)).get("status");
    }

    private long emailDokumente(String mandant) throws SQLException {
        return wert("select count(*) from " + schema(mandant) + ".dokument where quelle = 'EMAIL'");
    }

    private long wert(String sql, Object... parameter) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < parameter.length; i++) {
                abfrage.setObject(i + 1, parameter[i]);
            }
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                return zeile.getLong(1);
            }
        }
    }

    private Map<String, Object> zeile(String sql, Object parameter) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, parameter);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                Map<String, Object> werte = new HashMap<>();
                for (int i = 1; i <= zeile.getMetaData().getColumnCount(); i++) {
                    werte.put(zeile.getMetaData().getColumnLabel(i), zeile.getObject(i));
                }
                return werte;
            }
        }
    }

    private void ausfuehren(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.executeUpdate();
        }
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }

    private record Teil(String name, byte[] inhalt, String mime) {
    }
}
