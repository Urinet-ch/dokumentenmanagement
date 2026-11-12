package ch.urinet.archiv.protokoll.adapter.out.tsa;

import ch.urinet.archiv.protokoll.application.ZeitstempelPort;
import ch.urinet.archiv.protokoll.domain.Pruefdaten;
import ch.urinet.archiv.protokoll.domain.Stempel;
import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.cms.SignerInformationVerifier;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenInfo;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CRLException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RFC-3161-Zeitstempel mit BouncyCastle (Req 15.3, 15.11, 15.12; 4.9-E1): An den Dienst geht nur der SHA-256-Imprint
 * (Req 16.7). Eine Antwort gilt nur, wenn Status, Nonce und Imprint stimmen, die Policy-OID und die ausstellende CA in
 * der Allowlist des Dienstes stehen und die Signatur des Tokens mit dem TSA-Zertifikat (kritische EKU timeStamping)
 * besteht. Prüfdaten (OCSP oder CRL) kommen nur von den konfigurierten Adressen, nie aus dem Zertifikat (AIA).
 */
@ApplicationScoped
public class BcZeitstempelAdapter implements ZeitstempelPort {

    static final int MAX_ANTWORT = 1024 * 1024;
    static final int MAX_CRL = 8 * 1024 * 1024;

    private final Config config;
    private final Duration zeitgrenze;
    private final Map<String, TsaDienst> dienste = new ConcurrentHashMap<>();
    private final SecureRandom zufall = new SecureRandom();
    private final HttpClient client;

    public BcZeitstempelAdapter(Config config,
                                @ConfigProperty(name = "archiv.tsa.zeitgrenze", defaultValue = "PT30S") Duration zeitgrenze) {
        this.config = config;
        this.zeitgrenze = zeitgrenze;
        this.client = HttpClient.newBuilder().connectTimeout(zeitgrenze)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public List<String> dienste() {
        return TsaDienst.kennungen(config);
    }

    private TsaDienst dienst(String kennung) {
        if (!dienste().contains(kennung)) {
            throw new ZeitstempelFehler("Zeitstempeldienst " + kennung + " ist nicht konfiguriert");
        }
        try {
            return dienste.computeIfAbsent(kennung, k -> TsaDienst.laden(config, k));
        } catch (IllegalStateException e) {
            throw new ZeitstempelFehler("Zeitstempeldienst " + kennung + " ist falsch konfiguriert", e);
        }
    }

    @Override
    public Stempel stempeln(String kennung, byte[] imprint) {
        TsaDienst d = dienst(kennung);
        try {
            TimeStampRequestGenerator generator = new TimeStampRequestGenerator();
            generator.setCertReq(true);
            TimeStampRequest anfrage = generator.generate(TSPAlgorithms.SHA256, imprint, new BigInteger(64, zufall));
            byte[] antwort = senden(d.url(), "application/timestamp-query", anfrage.getEncoded(), MAX_ANTWORT);
            TimeStampResponse resp = antwort(antwort);
            resp.validate(anfrage);
            return geprueft(kennung, Allowlist.von(d), antwort, imprint, List.of());
        } catch (TSPException | IOException e) {
            throw new ZeitstempelFehler("Zeitstempel von " + kennung + " nicht erhalten oder ungültig", e);
        }
    }

    @Override
    public Stempel pruefen(String kennung, byte[] tsr, byte[] imprint) {
        return geprueft(kennung, Allowlist.von(dienst(kennung)), tsr, imprint, List.of());
    }

    @Override
    public Pruefdaten pruefdaten(String kennung, Stempel stempel) {
        TsaDienst d = dienst(kennung);
        List<X509Certificate> kette = kette(stempel.kettePem());
        X509Certificate tsa = kette.get(0);
        X509Certificate ca = kette.get(1);
        try {
            if (d.ocsp().isPresent()) {
                OCSPReqBuilder builder = new OCSPReqBuilder();
                builder.addRequest(zertifikatId(tsa, ca));
                byte[] antwort = senden(d.ocsp().get(), "application/ocsp-request", builder.build().getEncoded(),
                        MAX_ANTWORT);
                ocspPruefen(antwort, tsa, ca);
                return new Pruefdaten(Pruefdaten.Art.OCSP, antwort);
            }
            byte[] crl = holen(d.crl().orElseThrow(), MAX_CRL);
            crlPruefen(crl, tsa, ca);
            return new Pruefdaten(Pruefdaten.Art.CRL, crl);
        } catch (OCSPException | IOException e) {
            throw new ZeitstempelFehler("Prüfdaten für " + kennung + " nicht erhalten", e);
        }
    }

    @Override
    public Instant pruefenAbgelegt(String kennung, byte[] tsr, byte[] imprint, String kettePem, Pruefdaten pruefdaten) {
        Allowlist erlaubt = dienste().contains(kennung) ? Allowlist.von(dienst(kennung)) : alleDienste();
        List<X509Certificate> kette = kette(kettePem);
        Stempel stempel = geprueft(kennung, erlaubt, tsr, imprint, kette);
        List<X509Certificate> geprueft = kette(stempel.kettePem());
        if (!gleich(geprueft.get(0), kette.get(0)) || !gleich(geprueft.get(1), kette.get(1))) {
            throw new ZeitstempelFehler("Abgelegte Zertifikatskette passt nicht zum Zeitstempel von " + kennung);
        }
        switch (pruefdaten.art()) {
            case OCSP -> ocspPruefen(pruefdaten.inhalt(), kette.get(0), kette.get(1));
            case CRL -> crlPruefen(pruefdaten.inhalt(), kette.get(0), kette.get(1));
        }
        return stempel.genTime();
    }

    private Allowlist alleDienste() {
        Set<String> policies = new HashSet<>();
        List<X509Certificate> cas = new ArrayList<>();
        for (String k : dienste()) {
            TsaDienst d = dienst(k);
            policies.addAll(d.policies());
            cas.addAll(d.cas());
        }
        if (cas.isEmpty()) {
            throw new ZeitstempelFehler("Keine Zeitstempeldienste konfiguriert (archiv.tsa.dienste)");
        }
        return new Allowlist(policies, cas);
    }

    /** Prüft ein Token gegen Imprint und Allowlist; {@code zusatz} sind weitere Zertifikate (abgelegte Kette). */
    static Stempel geprueft(String kennung, Allowlist erlaubt, byte[] roh, byte[] imprint,
                            List<X509Certificate> zusatz) {
        try {
            TimeStampResponse resp = antwort(roh);
            int status = resp.getStatus();
            if (status != 0 && status != 1) {
                throw new ZeitstempelFehler("Zeitstempeldienst " + kennung + " hat abgelehnt (Status " + status + ")");
            }
            TimeStampToken token = resp.getTimeStampToken();
            if (token == null) {
                throw new ZeitstempelFehler("Antwort von " + kennung + " enthält kein Token");
            }
            TimeStampTokenInfo info = token.getTimeStampInfo();
            if (!TSPAlgorithms.SHA256.equals(info.getMessageImprintAlgOID())
                    || !Arrays.equals(info.getMessageImprintDigest(), imprint)) {
                throw new ZeitstempelFehler("Zeitstempel von " + kennung + " gehört nicht zu diesem Manifest");
            }
            if (!erlaubt.policies().contains(info.getPolicy().getId())) {
                throw new ZeitstempelFehler("Policy des Zeitstempels von " + kennung + " ist nicht erlaubt");
            }
            X509CertificateHolder tsaHolder = tsaZertifikat(token, zusatz)
                    .orElseThrow(() -> new ZeitstempelFehler("Zeitstempel von " + kennung + " ohne TSA-Zertifikat"));
            SignerInformationVerifier verifier = new JcaSimpleSignerInfoVerifierBuilder().build(tsaHolder);
            token.validate(verifier);
            X509Certificate tsa = new JcaX509CertificateConverter().getCertificate(tsaHolder);
            Instant genTime = info.getGenTime().toInstant();
            X509Certificate ca = aussteller(tsa, erlaubt.cas(), genTime)
                    .orElseThrow(() -> new ZeitstempelFehler("TSA-Zertifikat von " + kennung
                            + " stammt nicht von einer erlaubten CA"));
            return new Stempel(kennung, roh.clone(), genTime, pem(tsa) + pem(ca));
        } catch (TSPException | OperatorCreationException | CertificateException e) {
            throw new ZeitstempelFehler("Zeitstempel von " + kennung + " ist ungültig", e);
        }
    }

    private static Optional<X509CertificateHolder> tsaZertifikat(TimeStampToken token, List<X509Certificate> zusatz)
            throws CertificateException {
        List<X509CertificateHolder> kandidaten = new ArrayList<>(token.getCertificates().getMatches(null));
        for (X509Certificate z : zusatz) {
            kandidaten.add(new JcaX509CertificateHolder(z));
        }
        return kandidaten.stream().filter(h -> token.getSID().match(h)).findFirst();
    }

    private static Optional<X509Certificate> aussteller(X509Certificate tsa, List<X509Certificate> cas, Instant zeit) {
        for (X509Certificate ca : cas) {
            if (!ca.getSubjectX500Principal().equals(tsa.getIssuerX500Principal())) {
                continue;
            }
            try {
                tsa.verify(ca.getPublicKey());
                ca.checkValidity(Date.from(zeit));
                return Optional.of(ca);
            } catch (GeneralSecurityException e) {
                // nächste CA der Allowlist
            }
        }
        return Optional.empty();
    }

    static void ocspPruefen(byte[] inhalt, X509Certificate tsa, X509Certificate ca) {
        try {
            OCSPResp resp = new OCSPResp(inhalt);
            if (resp.getStatus() != OCSPResp.SUCCESSFUL || !(resp.getResponseObject() instanceof BasicOCSPResp basic)) {
                throw new ZeitstempelFehler("OCSP-Antwort ohne Ergebnis");
            }
            if (!ocspSignaturGueltig(basic, ca)) {
                throw new ZeitstempelFehler("OCSP-Antwort ist nicht von der CA signiert");
            }
            CertificateID id = zertifikatId(tsa, ca);
            boolean gefunden = false;
            for (SingleResp einzel : basic.getResponses()) {
                if (einzel.getCertID().equals(id)) {
                    gefunden = true;
                    if (einzel.getCertStatus() != null) {
                        throw new ZeitstempelFehler("TSA-Zertifikat ist laut OCSP gesperrt oder unbekannt");
                    }
                }
            }
            if (!gefunden) {
                throw new ZeitstempelFehler("OCSP-Antwort betrifft nicht das TSA-Zertifikat");
            }
        } catch (IOException | OCSPException | OperatorCreationException | CertificateException e) {
            throw new ZeitstempelFehler("OCSP-Antwort ist ungültig", e);
        }
    }

    private static boolean ocspSignaturGueltig(BasicOCSPResp basic, X509Certificate ca)
            throws OperatorCreationException, OCSPException, CertificateException {
        if (basic.isSignatureValid(new JcaContentVerifierProviderBuilder().build(ca.getPublicKey()))) {
            return true;
        }
        JcaX509CertificateConverter umwandler = new JcaX509CertificateConverter();
        for (X509CertificateHolder holder : basic.getCerts()) {
            X509Certificate responder = umwandler.getCertificate(holder);
            try {
                responder.verify(ca.getPublicKey());
                responder.checkValidity(basic.getProducedAt());
                List<String> zwecke = responder.getExtendedKeyUsage();
                if (zwecke != null && zwecke.contains(KeyPurposeId.id_kp_OCSPSigning.getId())
                        && basic.isSignatureValid(new JcaContentVerifierProviderBuilder().build(holder))) {
                    return true;
                }
            } catch (GeneralSecurityException e) {
                // nächster Responder
            }
        }
        return false;
    }

    static void crlPruefen(byte[] inhalt, X509Certificate tsa, X509Certificate ca) {
        try {
            X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509")
                    .generateCRL(new ByteArrayInputStream(inhalt));
            if (!crl.getIssuerX500Principal().equals(ca.getSubjectX500Principal())) {
                throw new ZeitstempelFehler("Sperrliste stammt nicht von der CA des TSA-Zertifikats");
            }
            crl.verify(ca.getPublicKey());
            if (crl.isRevoked(tsa)) {
                throw new ZeitstempelFehler("TSA-Zertifikat ist laut Sperrliste gesperrt");
            }
        } catch (CRLException | CertificateException e) {
            throw new ZeitstempelFehler("Sperrliste ist ungültig", e);
        } catch (GeneralSecurityException e) {
            throw new ZeitstempelFehler("Sperrliste ist nicht von der CA signiert", e);
        }
    }

    private static CertificateID zertifikatId(X509Certificate tsa, X509Certificate ca) {
        try {
            DigestCalculatorProvider rechner = new JcaDigestCalculatorProviderBuilder().build();
            return new CertificateID(rechner.get(CertificateID.HASH_SHA1), new JcaX509CertificateHolder(ca),
                    tsa.getSerialNumber());
        } catch (OperatorCreationException | OCSPException | CertificateException e) {
            throw new ZeitstempelFehler("OCSP-Anfrage nicht erstellbar", e);
        }
    }

    static TimeStampResponse antwort(byte[] tsr) {
        try {
            // lehnt nachfolgende Bytes ab, damit genau die geprüfte Antwort abgelegt wird
            ASN1Primitive.fromByteArray(tsr);
            return new TimeStampResponse(tsr);
        } catch (IOException | TSPException | IllegalArgumentException e) {
            throw new ZeitstempelFehler("Zeitstempel-Antwort ist kein gültiges RFC-3161-Format", e);
        }
    }

    static List<X509Certificate> kette(String pem) {
        try {
            List<X509Certificate> liste = TsaDienst.pem(pem);
            if (liste.size() != 2) {
                throw new ZeitstempelFehler("Zertifikatskette muss TSA-Zertifikat und CA enthalten");
            }
            liste.get(0).verify(liste.get(1).getPublicKey());
            return liste;
        } catch (GeneralSecurityException e) {
            throw new ZeitstempelFehler("Zertifikatskette ist ungültig", e);
        }
    }

    private static boolean gleich(X509Certificate a, X509Certificate b) {
        try {
            return Arrays.equals(a.getEncoded(), b.getEncoded());
        } catch (CertificateException e) {
            return false;
        }
    }

    static String pem(X509Certificate zertifikat) throws CertificateException {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(zertifikat.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    private byte[] senden(URI ziel, String typ, byte[] koerper, int max) throws IOException {
        HttpRequest anfrage = HttpRequest.newBuilder(ziel).timeout(zeitgrenze).header("Content-Type", typ)
                .POST(HttpRequest.BodyPublishers.ofByteArray(koerper)).build();
        return lesen(anfrage, max);
    }

    private byte[] holen(URI ziel, int max) throws IOException {
        return lesen(HttpRequest.newBuilder(ziel).timeout(zeitgrenze).GET().build(), max);
    }

    private byte[] lesen(HttpRequest anfrage, int max) throws IOException {
        try {
            HttpResponse<InputStream> antwort = client.send(anfrage, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream ein = antwort.body()) {
                if (antwort.statusCode() != 200) {
                    throw new IOException("HTTP " + antwort.statusCode());
                }
                byte[] daten = ein.readNBytes(max + 1);
                if (daten.length > max) {
                    throw new IOException("Antwort zu gross");
                }
                return daten;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("unterbrochen", e);
        }
    }

    /** Erlaubte Policy-OIDs und ausstellende CAs. */
    record Allowlist(Set<String> policies, List<X509Certificate> cas) {
        static Allowlist von(TsaDienst d) {
            return new Allowlist(new LinkedHashSet<>(d.policies()), d.cas());
        }
    }
}
