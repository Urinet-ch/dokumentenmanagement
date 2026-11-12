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
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Prüfung von RFC-3161-Antworten und Prüfdaten (Req 15.3, 15.11; 4.9-E1) gegen eine synthetische Test-PKI. */
class BcZeitstempelAdapterTest {

    private static final String POLICY = "1.3.6.1.4.1.99999.1";
    private static final TsaTestPki PKI = TsaTestPki.neu("bc");
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

        BcZeitstempelAdapter.ocspPruefen(PKI.ocsp(anfrage, false), PKI.tsa, PKI.ca);
        assertThatThrownBy(() -> BcZeitstempelAdapter.ocspPruefen(PKI.ocsp(anfrage, true), PKI.tsa, PKI.ca))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("gesperrt");
    }

    @Test
    void crlOhneEintragWirdAngenommenMitEintragOderVonFremderCaAbgelehnt() {
        BcZeitstempelAdapter.crlPruefen(PKI.crl(false), PKI.tsa, PKI.ca);

        assertThatThrownBy(() -> BcZeitstempelAdapter.crlPruefen(PKI.crl(true), PKI.tsa, PKI.ca))
                .isInstanceOf(ZeitstempelFehler.class).hasMessageContaining("gesperrt");
        TsaTestPki fremd = TsaTestPki.neu("bc");
        assertThatThrownBy(() -> BcZeitstempelAdapter.crlPruefen(fremd.crl(false), PKI.tsa, PKI.ca))
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
}
