package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Synthetische Testdateien für den OCR-Adapter (keine echten Belege, Req 16.15): Fotos mit gezeichnetem Text,
 * Mehrseiten-PDF ohne Textebene, PDF mit Textebene, verschlüsseltes und signiertes PDF. HEIC liegt als Fixture unter
 * {@code src/test/resources/ocr/} (Herkunft dort in der README).
 */
public final class OcrTestdateien {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private OcrTestdateien() {
    }

    /** Bild einer Seite mit Textzeilen, wahlweise um {@code grad} gedreht (schief fotografiert). */
    public static BufferedImage seite(double grad, String... zeilen) {
        BufferedImage bild = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bild.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, bild.getWidth(), bild.getHeight());
        g.rotate(Math.toRadians(grad), bild.getWidth() / 2.0, bild.getHeight() / 2.0);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 40));
        for (int i = 0; i < zeilen.length; i++) {
            g.drawString(zeilen[i], 120, 200 + i * 80);
        }
        g.dispose();
        return bild;
    }

    public static byte[] jpeg(BufferedImage bild) {
        return bild("jpg", bild);
    }

    public static byte[] png(BufferedImage bild) {
        return bild("png", bild);
    }

    private static byte[] bild(String format, BufferedImage bild) {
        try {
            var aus = new ByteArrayOutputStream();
            if (!ImageIO.write(bild, format, aus)) {
                throw new IllegalStateException("Kein Writer für " + format);
            }
            return aus.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] heic() {
        try (InputStream ein = OcrTestdateien.class.getResourceAsStream("/ocr/foto.heic")) {
            return ein.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** PDF aus Bildern, ohne Textebene (gescannt). */
    public static byte[] bildPdf(List<BufferedImage> seiten) {
        try (PDDocument dok = new PDDocument()) {
            for (BufferedImage bild : seiten) {
                PDPage seite = new PDPage(PDRectangle.A4);
                dok.addPage(seite);
                var bildObjekt = LosslessFactory.createFromImage(dok, bild);
                try (var inhalt = new PDPageContentStream(dok, seite)) {
                    inhalt.drawImage(bildObjekt, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }
            return speichern(dok);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** PDF mit Textebene (digital erzeugt). */
    public static byte[] textPdf(String... zeilen) {
        try (PDDocument dok = new PDDocument()) {
            textSeite(dok, zeilen);
            return speichern(dok);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** PDF mit Benutzerpasswort: ohne Passwort nicht lesbar. */
    public static byte[] verschluesseltesPdf() {
        try (PDDocument dok = new PDDocument()) {
            textSeite(dok, "Verschluesselter Testbeleg");
            var schutz = new StandardProtectionPolicy("eigner-test", "benutzer-test", new AccessPermission());
            schutz.setEncryptionKeyLength(256);
            dok.protect(schutz);
            return speichern(dok);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** PDF mit Textebene und CMS-Signatur (selbstsigniertes Testzertifikat, nur im Speicher). */
    public static byte[] signiertesPdf() {
        try {
            byte[] unsigniert = textPdf("Signierter Testbeleg", "Vertrag Muster");
            KeyPairGenerator erzeuger = KeyPairGenerator.getInstance("RSA");
            erzeuger.initialize(2048);
            KeyPair paar = erzeuger.generateKeyPair();
            X509Certificate zertifikat = zertifikat(paar);
            try (PDDocument dok = Loader.loadPDF(unsigniert)) {
                PDSignature signatur = new PDSignature();
                signatur.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
                signatur.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
                signatur.setName("Test");
                signatur.setSignDate(Calendar.getInstance());
                dok.addSignature(signatur, inhalt -> {
                    try {
                        ContentSigner signierer = new JcaContentSignerBuilder("SHA256withRSA").build(paar.getPrivate());
                        var generator = new CMSSignedDataGenerator();
                        generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                                new JcaDigestCalculatorProviderBuilder().build()).build(signierer, zertifikat));
                        generator.addCertificates(new JcaCertStore(List.of(zertifikat)));
                        return generator.generate(new CMSProcessableByteArray(inhalt.readAllBytes()), false)
                                .getEncoded();
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                });
                var aus = new ByteArrayOutputStream();
                dok.saveIncremental(aus);
                return aus.toByteArray();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static X509Certificate zertifikat(KeyPair paar) throws Exception {
        var name = new X500Name("CN=Testsignatur UriNet Archiv");
        Instant jetzt = Instant.now();
        var bauer = new JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date.from(jetzt.minus(Duration.ofDays(1))),
                Date.from(jetzt.plus(Duration.ofDays(30))), name, paar.getPublic());
        return new JcaX509CertificateConverter().getCertificate(bauer.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(paar.getPrivate())));
    }

    private static void textSeite(PDDocument dok, String... zeilen) throws IOException {
        PDPage seite = new PDPage(PDRectangle.A4);
        dok.addPage(seite);
        try (var inhalt = new PDPageContentStream(dok, seite)) {
            inhalt.beginText();
            inhalt.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
            inhalt.newLineAtOffset(72, 760);
            for (String zeile : zeilen) {
                inhalt.showText(zeile);
                inhalt.newLineAtOffset(0, -20);
            }
            inhalt.endText();
        }
    }

    private static byte[] speichern(PDDocument dok) throws IOException {
        var aus = new ByteArrayOutputStream();
        dok.save(aus);
        return aus.toByteArray();
    }
}
