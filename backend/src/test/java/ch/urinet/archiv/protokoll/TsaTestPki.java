package ch.urinet.archiv.protokoll;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.Req;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.jcajce.JcaBasicOCSPRespBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Synthetische PKI eines Zeitstempeldienstes für Tests (Aufgabe 5.2): CA, TSA-Zertifikat mit kritischer EKU
 * timeStamping, dazu RFC-3161-, OCSP- und CRL-Antworten. Nur Testschlüssel, nie echte Dienste (Auftrag: TSA nur über
 * WireMock).
 */
public final class TsaTestPki {

    private static final SecureRandom ZUFALL = new SecureRandom();

    public final KeyPair caSchluessel;
    public final X509Certificate ca;
    public final KeyPair tsaSchluessel;
    public final X509Certificate tsa;

    private TsaTestPki(KeyPair caSchluessel, X509Certificate ca, KeyPair tsaSchluessel, X509Certificate tsa) {
        this.caSchluessel = caSchluessel;
        this.ca = ca;
        this.tsaSchluessel = tsaSchluessel;
        this.tsa = tsa;
    }

    /** CA und TSA-Zertifikat mit dem Namen {@code name}; gültig ab gestern für 10 Jahre. */
    public static TsaTestPki neu(String name) {
        try {
            KeyPair caSchluessel = rsa();
            X500Name caName = new X500Name("CN=Test-CA " + name + ",O=Synthetisch");
            Instant ab = Instant.now().minus(Duration.ofDays(1));
            Instant bis = ab.plus(Duration.ofDays(3650));
            X509v3CertificateBuilder caBau = new JcaX509v3CertificateBuilder(caName, BigInteger.ONE, Date.from(ab),
                    Date.from(bis), caName, caSchluessel.getPublic());
            caBau.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            caBau.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            X509Certificate ca = zertifikat(caBau, caSchluessel);

            KeyPair tsaSchluessel = rsa();
            X509v3CertificateBuilder tsaBau = new JcaX509v3CertificateBuilder(caName, BigInteger.TWO, Date.from(ab),
                    Date.from(bis), new X500Name("CN=Test-TSA " + name + ",O=Synthetisch"), tsaSchluessel.getPublic());
            tsaBau.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
            tsaBau.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            return new TsaTestPki(caSchluessel, ca, tsaSchluessel, zertifikat(tsaBau, caSchluessel));
        } catch (Exception e) {
            throw new IllegalStateException("Test-PKI nicht erzeugt", e);
        }
    }

    /** RFC-3161-Antwort auf die Anfrage, mit Policy {@code policy}; das Token trägt das TSA-Zertifikat. */
    public byte[] tsr(byte[] anfrage, String policy) {
        try {
            return tsr(new TimeStampRequest(anfrage), policy);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Zeitstempel-Anfrage nicht lesbar", e);
        }
    }

    public byte[] tsr(TimeStampRequest anfrage, String policy) {
        try {
            var signer = new JcaSimpleSignerInfoGeneratorBuilder().build("SHA256withRSA", tsaSchluessel.getPrivate(), tsa);
            var generator = new TimeStampTokenGenerator(signer, new JcaDigestCalculatorProviderBuilder().build()
                    .get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1)), new ASN1ObjectIdentifier(policy));
            generator.addCertificates(new JcaCertStore(List.of(tsa)));
            return new TimeStampResponseGenerator(generator, TSPAlgorithms.ALLOWED)
                    .generate(anfrage, new BigInteger(64, ZUFALL), new Date()).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Zeitstempel nicht erzeugt", e);
        }
    }

    /** Signierte OCSP-Antwort der CA: jedes angefragte Zertifikat gut, mit {@code gesperrt} widerrufen. */
    public byte[] ocsp(byte[] anfrage, boolean gesperrt) {
        Instant jetzt = Instant.now();
        return ocsp(anfrage, gesperrt, jetzt, jetzt, null);
    }

    /** OCSP-Antwort mit wählbaren Zeiten {@code producedAt}, {@code thisUpdate} und {@code nextUpdate} (optional). */
    public byte[] ocsp(byte[] anfrage, boolean gesperrt, Instant erstellt, Instant diesesUpdate, Instant naechstesUpdate) {
        try {
            OCSPReq req = new OCSPReq(anfrage);
            var bau = new JcaBasicOCSPRespBuilder(ca.getPublicKey(), new JcaDigestCalculatorProviderBuilder().build()
                    .get(org.bouncycastle.cert.ocsp.CertificateID.HASH_SHA1));
            for (Req einzel : req.getRequestList()) {
                bau.addResponse(einzel.getCertID(), gesperrt
                        ? new RevokedStatus(new Date(), CRLReason.keyCompromise) : CertificateStatus.GOOD,
                        Date.from(diesesUpdate), naechstesUpdate == null ? null : Date.from(naechstesUpdate), null);
            }
            BasicOCSPResp basic = bau.build(signer(caSchluessel), null, Date.from(erstellt));
            return new OCSPRespBuilder().build(OCSPResp.SUCCESSFUL, basic).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("OCSP-Antwort nicht erzeugt", e);
        }
    }

    /**
     * OCSP-Antwort eines delegierten Responders mit eigenem RSA-Schlüssel von {@code bits} Bit (anders lang als der
     * CA-Schlüssel, wie bei den Schweizer Diensten). Das Responder-Zertifikat trägt EKU OCSPSigning, falls
     * {@code ocspEku}, und ist von der CA ausgestellt, ausser {@code fremdAusgestellt}.
     */
    public byte[] delegierteOcsp(byte[] anfrage, int bits, boolean ocspEku, boolean fremdAusgestellt) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits, ZUFALL);
            KeyPair responder = generator.generateKeyPair();
            KeyPair aussteller = fremdAusgestellt ? rsa() : caSchluessel;
            Instant jetzt = Instant.now();
            X509v3CertificateBuilder bau = new JcaX509v3CertificateBuilder(
                    new JcaX509CertificateHolder(ca).getSubject(), new BigInteger(64, ZUFALL),
                    Date.from(jetzt.minus(Duration.ofDays(1))), Date.from(jetzt.plus(Duration.ofDays(30))),
                    new X500Name("CN=Test OCSP-Responder"), responder.getPublic());
            if (ocspEku) {
                bau.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_OCSPSigning));
            }
            X509Certificate zert = zertifikat(bau, aussteller);
            OCSPReq req = new OCSPReq(anfrage);
            var antwort = new JcaBasicOCSPRespBuilder(responder.getPublic(),
                    new JcaDigestCalculatorProviderBuilder().build().get(org.bouncycastle.cert.ocsp.CertificateID.HASH_SHA1));
            for (Req einzel : req.getRequestList()) {
                antwort.addResponse(einzel.getCertID(), CertificateStatus.GOOD, Date.from(jetzt), null, null);
            }
            BasicOCSPResp basic = antwort.build(signer(responder),
                    new org.bouncycastle.cert.X509CertificateHolder[] {new JcaX509CertificateHolder(zert)},
                    Date.from(jetzt));
            return new OCSPRespBuilder().build(OCSPResp.SUCCESSFUL, basic).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("OCSP-Antwort nicht erzeugt", e);
        }
    }

    /** Sperrliste der CA; mit {@code gesperrt} steht das TSA-Zertifikat darauf. */
    public byte[] crl(boolean gesperrt) {
        Instant jetzt = Instant.now();
        return crl(gesperrt, jetzt, jetzt.plus(Duration.ofDays(7)));
    }

    /** Sperrliste mit wählbarem {@code thisUpdate} und {@code nextUpdate}. */
    public byte[] crl(boolean gesperrt, Instant diesesUpdate, Instant naechstesUpdate) {
        try {
            var bau = new X509v2CRLBuilder(new JcaX509CertificateHolder(ca).getSubject(), Date.from(diesesUpdate));
            bau.setNextUpdate(Date.from(naechstesUpdate));
            if (gesperrt) {
                bau.addCRLEntry(tsa.getSerialNumber(), new Date(), CRLReason.keyCompromise);
            }
            return bau.build(signer(caSchluessel)).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Sperrliste nicht erzeugt", e);
        }
    }

    public String caPem() {
        return pem(ca);
    }

    public static String pem(X509Certificate zertifikat) {
        try {
            return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(zertifikat.getEncoded()) + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Synthetisches Ed25519-Paar des Manifests als PKCS#8- bzw. SPKI-PEM. */
    public static String[] ed25519Pem() {
        try {
            KeyPair paar = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new String[] {pem("PRIVATE KEY", paar.getPrivate().getEncoded()),
                    pem("PUBLIC KEY", paar.getPublic().getEncoded())};
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String typ, byte[] inhalt) {
        return "-----BEGIN " + typ + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(inhalt) + "\n-----END " + typ + "-----\n";
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, ZUFALL);
        return generator.generateKeyPair();
    }

    private static ContentSigner signer(KeyPair schluessel) throws Exception {
        return new JcaContentSignerBuilder("SHA256withRSA").build(schluessel.getPrivate());
    }

    private static X509Certificate zertifikat(X509v3CertificateBuilder bau, KeyPair aussteller) throws Exception {
        return new JcaX509CertificateConverter().getCertificate(bau.build(signer(aussteller)));
    }
}
