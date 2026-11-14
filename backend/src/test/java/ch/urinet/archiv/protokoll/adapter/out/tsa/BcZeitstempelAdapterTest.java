package ch.urinet.archiv.protokoll.adapter.out.tsa;

import ch.urinet.archiv.protokoll.TsaTestPki;
import ch.urinet.archiv.protokoll.application.ZeitstempelPort.ZeitstempelFehler;
import ch.urinet.archiv.protokoll.domain.Sha256;
import ch.urinet.archiv.protokoll.domain.Stempel;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import ch.urinet.archiv.protokoll.domain.Pruefdaten;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Prüfung von RFC-3161-Antworten und Prüfdaten (Req 15.3, 15.11; 4.9-E1) gegen eine synthetische Test-PKI. */
class BcZeitstempelAdapterTest {

    private static final String POLICY = "1.3.6.1.4.1.99999.1";
    private static final TsaTestPki PKI = TsaTestPki.neu("bc");
    private static final Duration HOECHSTALTER = Duration.ofDays(7);
    private static final byte[] IMPRINT = Sha256.von("manifest".getBytes(StandardCharsets.UTF_8));

    private final BcZeitstempelAdapter.Allowlist erlaubt =
            new BcZeitstempelAdapter.Allowlist(Set.of(POLICY), List.of(PKI.ca));

    @Test
    void eineGueltigeAntwortLiefertZeitUndKette() {
        Stempel stempel = BcZeitstempelAdapter.geprueft("haupt", erlaubt, PKI.tsr(anfrage(IMPRINT), POLICY), IMPRINT,
                List.of());

        assertThat(stempel.genTime()).isBetween(Instant.now().minus(Duration.ofMinutes(1)), Instant.now());
        assertThat(BcZeitstempelAdapter.kette(stempel.kettePem())).containsExactly(PKI.tsa, PKI.ca);
    }

    @Test
    void einFremderImprintWirdAbgelehnt() {
        byte[] tsr = PKI.tsr(anfrage(IMPRINT), POLICY);

        assertThatThrownBy(() -> BcZeitstempelAdapter.geprueft("haupt", erlaubt, tsr, new byte[32], List.of()))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("gehört nicht zu diesem Manifest");
    }

    @Test
    void eineNichtErlaubtePolicyWirdAbgelehnt() {
        byte[] tsr = PKI.tsr(anfrage(IMPRINT), "1.3.6.1.4.1.99999.2");

        assertThatThrownBy(() -> BcZeitstempelAdapter.geprueft("haupt", erlaubt, tsr, IMPRINT, List.of()))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("Policy");
    }

    @Test
    void eineNichtErlaubteCaWirdAbgelehnt() {
        TsaTestPki fremd = TsaTestPki.neu("bc");
        byte[] tsr = fremd.tsr(anfrage(IMPRINT), POLICY);

        assertThatThrownBy(() -> BcZeitstempelAdapter.geprueft("haupt", erlaubt, tsr, IMPRINT, List.of()))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("nicht von einer erlaubten CA");
    }

    @Test
    void angehaengteBytesWerdenAbgelehnt() {
        byte[] tsr = PKI.tsr(anfrage(IMPRINT), POLICY);
        byte[] laenger = Arrays.copyOf(tsr, tsr.length + 1);

        assertThatThrownBy(() -> BcZeitstempelAdapter.geprueft("haupt", erlaubt, laenger, IMPRINT, List.of()))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("RFC-3161");
    }

    @Test
    void ocspGutWirdAngenommenGesperrtAbgelehnt() throws Exception {
        CertificateID id = new CertificateID(new JcaDigestCalculatorProviderBuilder().build()
                .get(CertificateID.HASH_SHA1), new JcaX509CertificateHolder(PKI.ca), PKI.tsa.getSerialNumber());
        byte[] anfrage = new OCSPReqBuilder().addRequest(id).build().getEncoded();

        BcZeitstempelAdapter.ocspPruefen(PKI.ocsp(anfrage, false), PKI.tsa, PKI.ca, Instant.now(), HOECHSTALTER);
        assertThatThrownBy(() -> BcZeitstempelAdapter.ocspPruefen(PKI.ocsp(anfrage, true), PKI.tsa, PKI.ca,
                Instant.now(), HOECHSTALTER))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("gesperrt");
    }

    @Test
    void delegierterResponderMitAndererSchluessellaengeWirdAngenommen() throws Exception {
        // 3072 Bit gegen den 2048-Bit-Schlüssel der CA: die JCA wirft dabei, statt false zu liefern
        BcZeitstempelAdapter.ocspPruefen(PKI.delegierteOcsp(ocspAnfrage(), 3072, true, false), PKI.tsa, PKI.ca,
                Instant.now(), HOECHSTALTER);
    }

    @Test
    void delegierterResponderOhneOcspEkuOderNichtVonDerCaWirdOhneRohAusnahmeAbgelehnt() throws Exception {
        assertThatThrownBy(() -> BcZeitstempelAdapter.ocspPruefen(PKI.delegierteOcsp(ocspAnfrage(), 3072, false, false),
                PKI.tsa, PKI.ca, Instant.now(), HOECHSTALTER))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("nicht von der CA signiert");
        assertThatThrownBy(() -> BcZeitstempelAdapter.ocspPruefen(PKI.delegierteOcsp(ocspAnfrage(), 2048, true, true),
                PKI.tsa, PKI.ca, Instant.now(), HOECHSTALTER))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("nicht von der CA signiert");
        assertThatThrownBy(() -> BcZeitstempelAdapter.ocspPruefen(PKI.delegierteOcsp(ocspAnfrage(), 3072, true, true),
                PKI.tsa, PKI.ca, Instant.now(), HOECHSTALTER))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("nicht von der CA signiert");
    }

    @Test
    void eineAlteOderVorDemStempelAbgelaufeneOcspAntwortWirdAbgelehnt() throws Exception {
        byte[] anfrage = ocspAnfrage();
        Instant genTime = Instant.now();
        Instant alt = genTime.minus(Duration.ofDays(8));

        BcZeitstempelAdapter.ocspPruefen(PKI.ocsp(anfrage, false, genTime.minus(Duration.ofDays(6)),
                genTime.minus(Duration.ofDays(6)), genTime.plus(Duration.ofDays(1))), PKI.tsa, PKI.ca, genTime,
                HOECHSTALTER);
        assertThatThrownBy(() -> BcZeitstempelAdapter.ocspPruefen(PKI.ocsp(anfrage, false, alt, alt, null), PKI.tsa,
                PKI.ca, genTime, HOECHSTALTER))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("zu alt");
        Instant vorher = genTime.minus(Duration.ofHours(2));
        assertThatThrownBy(() -> BcZeitstempelAdapter.ocspPruefen(PKI.ocsp(anfrage, false, vorher, vorher,
                genTime.minus(Duration.ofHours(1))), PKI.tsa, PKI.ca, genTime, HOECHSTALTER))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("abgelaufen");
    }

    @Test
    void eineVeralteteSperrlisteWirdAbgelehnt() {
        Instant genTime = Instant.now();

        BcZeitstempelAdapter.crlPruefen(PKI.crl(false, genTime.plus(Duration.ofDays(2)),
                genTime.plus(Duration.ofDays(9))), PKI.tsa, PKI.ca, genTime, HOECHSTALTER);
        assertThatThrownBy(() -> BcZeitstempelAdapter.crlPruefen(PKI.crl(false, genTime.minus(Duration.ofDays(8)),
                genTime.plus(Duration.ofDays(30))), PKI.tsa, PKI.ca, genTime, HOECHSTALTER))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("zu alt");
        assertThatThrownBy(() -> BcZeitstempelAdapter.crlPruefen(PKI.crl(false, genTime.minus(Duration.ofDays(3)),
                genTime.minus(Duration.ofSeconds(1))), PKI.tsa, PKI.ca, genTime, HOECHSTALTER))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("abgelaufen");
    }

    @Test
    void einAusgetragenerDienstWirdGegenSeineEigeneAllowlistGeprueft(@TempDir Path ordner) throws Exception {
        TsaTestPki b = TsaTestPki.neu("b");
        String policyB = "1.3.6.1.4.1.99999.7";
        Map<String, String> werte = new HashMap<>();
        werte.put("archiv.tsa.dienste", "a");
        block(werte, ordner, "a", PKI, POLICY);
        block(werte, ordner, "b", b, policyB);
        BcZeitstempelAdapter adapter = adapter(werte);
        byte[] tsrB = b.tsr(anfrage(IMPRINT), policyB);
        String ketteB = TsaTestPki.pem(b.tsa) + TsaTestPki.pem(b.ca);
        Pruefdaten crlB = new Pruefdaten(Pruefdaten.Art.CRL, b.crl(false));

        assertThat(adapter.dienste()).containsExactly("a");
        assertThat(adapter.pruefenAbgelegt("b", tsrB, IMPRINT, ketteB, crlB))
                .isBetween(Instant.now().minus(Duration.ofMinutes(1)), Instant.now());
        assertThatThrownBy(() -> adapter.pruefenAbgelegt("a", tsrB, IMPRINT, ketteB, crlB))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("Policy");

        werte.keySet().removeIf(k -> k.startsWith("archiv.tsa.dienst.b."));
        assertThatThrownBy(() -> adapter(werte).pruefenAbgelegt("b", tsrB, IMPRINT, ketteB, crlB))
                .isInstanceOf(ZeitstempelFehler.class).hasMessage("Zeitstempeldienst b nicht mehr konfiguriert");
    }

    @Test
    void dasHoechstalterDerPruefdatenGiltJeDienst(@TempDir Path ordner) throws Exception {
        TsaTestPki b = TsaTestPki.neu("b");
        Map<String, String> werte = new HashMap<>();
        werte.put("archiv.tsa.dienste", "a,b");
        block(werte, ordner, "a", PKI, POLICY);
        block(werte, ordner, "b", b, POLICY);
        werte.put("archiv.tsa.dienst.b.pruefdaten-hoechstalter", "P40D");
        Instant jetzt = Instant.now();
        Instant ausgestellt = jetzt.minus(Duration.ofDays(30));
        Instant naechstes = jetzt.plus(Duration.ofDays(30));
        byte[] tsrA = PKI.tsr(anfrage(IMPRINT), POLICY);
        byte[] tsrB = b.tsr(anfrage(IMPRINT), POLICY);
        Pruefdaten crlA = new Pruefdaten(Pruefdaten.Art.CRL, PKI.crl(false, ausgestellt, naechstes));
        Pruefdaten crlB = new Pruefdaten(Pruefdaten.Art.CRL, b.crl(false, ausgestellt, naechstes));
        BcZeitstempelAdapter adapter = adapter(werte);

        assertThat(adapter.pruefenAbgelegt("b", tsrB, IMPRINT, TsaTestPki.pem(b.tsa) + TsaTestPki.pem(b.ca), crlB))
                .isAfter(jetzt.minus(Duration.ofMinutes(1)));
        assertThatThrownBy(() -> adapter.pruefenAbgelegt("a", tsrA, IMPRINT,
                TsaTestPki.pem(PKI.tsa) + TsaTestPki.pem(PKI.ca), crlA))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("zu alt");
        BcZeitstempelAdapter.Zeitraum zeitraum = BcZeitstempelAdapter.zeitraum(crlB);
        assertThat(zeitraum.thisUpdate()).isBetween(ausgestellt.minusSeconds(1), ausgestellt.plusSeconds(1));
        assertThat(zeitraum.nextUpdate()).isBetween(naechstes.minusSeconds(1), naechstes.plusSeconds(1));

        werte.put("archiv.tsa.dienst.b.pruefdaten-hoechstalter", "7 Tage");
        assertThatThrownBy(() -> adapter(werte).pruefenAbgelegt("b", tsrB, IMPRINT,
                TsaTestPki.pem(b.tsa) + TsaTestPki.pem(b.ca), crlB))
                .isInstanceOf(ZeitstempelFehler.class).hasMessage("Zeitstempeldienst b ist falsch konfiguriert");
    }

    @Test
    void einDienstMitPolicyEinesAnderenAberFremderCaWirdAbgelehnt(@TempDir Path ordner) throws Exception {
        TsaTestPki b = TsaTestPki.neu("b");
        Map<String, String> werte = new HashMap<>();
        werte.put("archiv.tsa.dienste", "a");
        block(werte, ordner, "a", PKI, POLICY);
        block(werte, ordner, "b", b, "1.3.6.1.4.1.99999.7");
        byte[] tsrA = PKI.tsr(anfrage(IMPRINT), POLICY);
        String ketteA = TsaTestPki.pem(PKI.tsa) + TsaTestPki.pem(PKI.ca);
        Pruefdaten crlA = new Pruefdaten(Pruefdaten.Art.CRL, PKI.crl(false));

        assertThatThrownBy(() -> adapter(werte).pruefenAbgelegt("b", tsrA, IMPRINT, ketteA, crlA))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("Policy");
    }

    private static void block(Map<String, String> werte, Path ordner, String kennung, TsaTestPki pki, String policy)
            throws Exception {
        Path ca = Files.writeString(ordner.resolve(kennung + "-ca.pem"), pki.caPem());
        String p = "archiv.tsa.dienst." + kennung + ".";
        werte.put(p + "url", "http://127.0.0.1:9/" + kennung + "/tsr");
        werte.put(p + "policies", policy);
        werte.put(p + "ca", ca.toAbsolutePath().toString());
        werte.put(p + "crl-url", "http://127.0.0.1:9/" + kennung + "/crl");
    }

    private static BcZeitstempelAdapter adapter(Map<String, String> werte) {
        Config config = new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(Map.copyOf(werte), "test"))
                .build();
        return new BcZeitstempelAdapter(config, Duration.ofSeconds(1), HOECHSTALTER);
    }

    private static byte[] ocspAnfrage() throws Exception {
        CertificateID id = new CertificateID(new JcaDigestCalculatorProviderBuilder().build()
                .get(CertificateID.HASH_SHA1), new JcaX509CertificateHolder(PKI.ca), PKI.tsa.getSerialNumber());
        return new OCSPReqBuilder().addRequest(id).build().getEncoded();
    }

    @Test
    void crlOhneEintragWirdAngenommenMitEintragOderVonFremderCaAbgelehnt() {
        BcZeitstempelAdapter.crlPruefen(PKI.crl(false), PKI.tsa, PKI.ca, Instant.now(), HOECHSTALTER);

        assertThatThrownBy(() -> BcZeitstempelAdapter.crlPruefen(PKI.crl(true), PKI.tsa, PKI.ca, Instant.now(),
                HOECHSTALTER))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("gesperrt");
        TsaTestPki fremd = TsaTestPki.neu("bc");
        assertThatThrownBy(() -> BcZeitstempelAdapter.crlPruefen(fremd.crl(false), PKI.tsa, PKI.ca, Instant.now(),
                HOECHSTALTER))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("nicht von der CA signiert");
    }

    private static byte[] anfrage(byte[] imprint) {
        try {
            TimeStampRequestGenerator generator = new TimeStampRequestGenerator();
            generator.setCertReq(true);
            return generator.generate(TSPAlgorithms.SHA256, imprint, BigInteger.TEN).getEncoded();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void eineTsaAntwortWirdNurBisZurHoechstgroesseGelesen() throws Exception {
        var klein = BcZeitstempelAdapter.begrenzt(4);
        AtomicBoolean abgebrochen = new AtomicBoolean();
        klein.onSubscribe(abo(abgebrochen));
        klein.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2}), ByteBuffer.wrap(new byte[] {3})));
        klein.onComplete();
        assertThat(klein.getBody().toCompletableFuture().get()).containsExactly(1, 2, 3);

        var gross = BcZeitstempelAdapter.begrenzt(4);
        gross.onSubscribe(abo(abgebrochen));
        gross.onNext(List.of(ByteBuffer.wrap(new byte[5])));
        assertThat(abgebrochen).isTrue();
        assertThatThrownBy(() -> gross.getBody().toCompletableFuture().get())
                .hasRootCauseMessage("Antwort zu gross");
    }

    private static Flow.Subscription abo(AtomicBoolean abgebrochen) {
        return new Flow.Subscription() {
            @Override
            public void request(long n) {
            }

            @Override
            public void cancel() {
                abgebrochen.set(true);
            }
        };
    }
}
