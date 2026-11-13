package ch.urinet.archiv.integration.adapter.out.imap;

import ch.urinet.archiv.integration.application.mail.PostfachPort.Anhang;
import jakarta.activation.DataHandler;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auswahl der Anhänge und des Textes (Spec 02, Req 1.2, 1.3): eingebettete Bilder sind kein Anhang, angehängte
 * Nachrichten werden bis zur Tiefe {@value MailTeile#MAX_TIEFE} gelesen. Die Nachrichten werden wie vom Server
 * geschrieben und wieder gelesen; nur synthetische Inhalte.
 */
class MailTeileTest {

    private static final Session SITZUNG = Session.getInstance(new Properties());
    private static final byte[] PDF = "%PDF-1.4\n% Test\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};
    /** wie {@code archiv.mail.eingebettet-ab} */
    private static final long SCHWELLE = 100 * 1024;

    @TempDir
    Path verzeichnis;

    @Test
    void htmlOhneMarkupSkripteUndStile() {
        assertThat(MailTeile.ohneMarkup("<html><head><style>p{}</style></head><body><p>Guten Tag</p>"
                + "<script>alert(1)</script>Rechnung&nbsp;&amp;&nbsp;Offerte<br>Gruss</body></html>"))
                .isEqualTo("Guten Tag\nRechnung & Offerte\nGruss");
    }

    @Test
    void eingebettetesLogoInMultipartRelatedIstKeinAnhangAuchMitDateinamenUndInline() throws Exception {
        MimeMultipart alternativ = new MimeMultipart("alternative");
        alternativ.addBodyPart(text("Bitte offerieren Sie", "plain"));
        alternativ.addBodyPart(text("<p>Bitte offerieren Sie</p><img src=\"cid:logo\">", "html"));
        MimeBodyPart huelle = new MimeBodyPart();
        huelle.setContent(alternativ);
        MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(huelle);
        related.addBodyPart(datei(PNG, "image/png", "image001.png", Part.INLINE, "<logo>"));
        related.addBodyPart(datei(PNG, "image/png", null, null, "<logo2>"));
        MailTeile teile = lesen(related);
        assertThat(teile.anhaenge()).isEmpty();
        assertThat(teile.text()).isEqualTo("Bitte offerieren Sie");
    }

    @Test
    void kleinesInlineBildMitContentIdIstAuchAusserhalbVonRelatedKeinAnhangInlineOhneContentIdSchon() throws Exception {
        MimeMultipart gemischt = new MimeMultipart("mixed");
        gemischt.addBodyPart(text("Hallo", "plain"));
        gemischt.addBodyPart(datei(PNG, "image/png", "logo.png", Part.INLINE, "<logo>"));
        // so hängt z. B. Apple Mail ein PDF an: inline, mit Dateiname, ohne Content-ID
        gemischt.addBodyPart(datei(PDF, "application/pdf", "rechnung.pdf", Part.INLINE, null));
        assertThat(namen(lesen(gemischt))).containsExactly("rechnung.pdf");
    }

    @Test
    void eingebetteteBilderAbDerSchwelleSindAnhaengeKleinereNicht() throws Exception {
        // Logo der Signatur, 8 KiB, in multipart/related
        MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(text("<p>Gruss</p><img src=\"cid:logo\">", "html"));
        related.addBodyPart(datei(bild(8 * 1024), "image/png", "image001.png", Part.INLINE, "<logo>"));
        assertThat(lesen(related).anhaenge()).isEmpty();
        // Foto aus Apple Mail: 300 KiB, inline mit Content-ID in multipart/mixed
        MimeMultipart apple = new MimeMultipart("mixed");
        apple.addBodyPart(text("Quittung", "plain"));
        apple.addBodyPart(datei(bild(300 * 1024), "image/jpeg", "IMG_0042.jpg", Part.INLINE, "<foto@apple>"));
        assertThat(namen(lesen(apple))).containsExactly("IMG_0042.jpg");
        // Foto aus Gmail: in multipart/related mit cid, ohne Disposition und ohne Namen
        MimeMultipart gmail = new MimeMultipart("related");
        gmail.addBodyPart(text("<p>Quittung</p><img src=\"cid:ii_abc\">", "html"));
        gmail.addBodyPart(datei(bild(300 * 1024), "image/jpeg", null, null, "<ii_abc>"));
        MailTeile teile = lesen(gmail);
        assertThat(teile.anhaenge()).hasSize(1);
        assertThat(teile.anhaenge().getFirst().name()).isEmpty();
        assertThat(Files.size(teile.anhaenge().getFirst().datei())).isEqualTo(300 * 1024);
        assertThat(teile.text()).isEqualTo("Quittung");
    }

    @Test
    void ohneDateinameNurMitAttachmentEinAnhangKodierteNamenDecodiert() throws Exception {
        MimeMultipart gemischt = new MimeMultipart("mixed");
        gemischt.addBodyPart(text("Hallo", "plain"));
        gemischt.addBodyPart(datei(PDF, "application/pdf", null, null, null));
        gemischt.addBodyPart(datei(PDF, "application/pdf", null, Part.ATTACHMENT, null));
        gemischt.addBodyPart(datei(PDF, "application/pdf", MimeUtility.encodeText("Grüsse für Zürich.pdf", "UTF-8",
                "Q"), Part.ATTACHMENT, null));
        MailTeile teile = lesen(gemischt);
        assertThat(teile.anhaenge()).hasSize(2);
        assertThat(teile.anhaenge().get(0).name()).isEmpty();
        assertThat(teile.anhaenge().get(1).name()).contains("Grüsse für Zürich.pdf");
        assertThat(Files.readAllBytes(teile.anhaenge().get(1).datei())).isEqualTo(PDF);
    }

    @Test
    void eineAlsNachrichtWeitergeleiteteRechnungLiefertIhrPdfBisZurGrenzeDerTiefe() throws Exception {
        MimeMultipart innen = new MimeMultipart("mixed");
        innen.addBodyPart(text("Rechnung anbei", "plain"));
        innen.addBodyPart(datei(PDF, "application/pdf", "tief-0.pdf", Part.ATTACHMENT, null));
        MimeMessage nachricht = nachricht(innen);
        for (int tiefe = 1; tiefe <= MailTeile.MAX_TIEFE + 1; tiefe++) {
            MimeMultipart aussen = new MimeMultipart("mixed");
            aussen.addBodyPart(text("Weitergeleitet " + tiefe, "plain"));
            MimeBodyPart weitergeleitet = new MimeBodyPart();
            weitergeleitet.setContent(nachricht, "message/rfc822");
            weitergeleitet.setDisposition(Part.ATTACHMENT);
            aussen.addBodyPart(weitergeleitet);
            nachricht = nachricht(aussen);
            if (tiefe == 1 || tiefe == MailTeile.MAX_TIEFE) {
                Path ziel = Files.createDirectory(verzeichnis.resolve("t" + tiefe));
                assertThat(namen(MailTeile.lesen(neuGelesen(nachricht), ziel, SCHWELLE))).containsExactly("tief-0.pdf");
            }
        }
        // eine Ebene über der Grenze bleibt die innerste Nachricht ungelesen: ihr PDF erscheint nicht
        Path ziel = Files.createDirectory(verzeichnis.resolve("tiefer"));
        assertThat(namen(MailTeile.lesen(neuGelesen(nachricht), ziel, SCHWELLE))).doesNotContain("tief-0.pdf");
    }

    private MailTeile lesen(MimeMultipart inhalt) throws Exception {
        return MailTeile.lesen(neuGelesen(nachricht(inhalt)), Files.createTempDirectory(verzeichnis, "m"), SCHWELLE);
    }

    private static List<String> namen(MailTeile teile) {
        return teile.anhaenge().stream().map(Anhang::name).flatMap(Optional::stream).toList();
    }

    private static MimeMessage nachricht(MimeMultipart inhalt) throws Exception {
        MimeMessage nachricht = new MimeMessage(SITZUNG);
        nachricht.setFrom("inhaber@example.invalid");
        nachricht.setSubject("Test");
        nachricht.setContent(inhalt);
        nachricht.saveChanges();
        return nachricht;
    }

    /** Wie vom Server: geschrieben und neu geparst. */
    private static MimeMessage neuGelesen(MimeMessage nachricht) throws Exception {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        nachricht.writeTo(aus);
        return new MimeMessage(SITZUNG, new ByteArrayInputStream(aus.toByteArray()));
    }

    private static byte[] bild(int groesse) {
        byte[] inhalt = new byte[groesse];
        new java.util.Random(groesse).nextBytes(inhalt);
        return inhalt;
    }

    private static MimeBodyPart text(String inhalt, String art) throws Exception {
        MimeBodyPart teil = new MimeBodyPart();
        teil.setText(inhalt, "UTF-8", art);
        return teil;
    }

    private static MimeBodyPart datei(byte[] inhalt, String typ, String name, String disposition, String contentId)
            throws Exception {
        MimeBodyPart teil = new MimeBodyPart();
        teil.setDataHandler(new DataHandler(new ByteArrayDataSource(inhalt, typ)));
        if (name != null) {
            teil.setFileName(name);
        }
        if (disposition != null) {
            teil.setDisposition(disposition);
        }
        if (contentId != null) {
            teil.setContentID(contentId);
        }
        return teil;
    }
}
