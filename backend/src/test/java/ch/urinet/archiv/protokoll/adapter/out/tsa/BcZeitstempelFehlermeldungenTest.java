package ch.urinet.archiv.protokoll.adapter.out.tsa;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.protokoll.TsaTestPki;
import ch.urinet.archiv.protokoll.application.ZeitstempelPort.ZeitstempelFehler;
import ch.urinet.archiv.protokoll.domain.Pruefdaten;
import ch.urinet.archiv.protokoll.domain.Sha256;
import ch.urinet.archiv.protokoll.domain.Stempel;
import com.sun.net.httpserver.HttpServer;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThat;

/** Alle Zeitstempel-Fehlerpfade melden eine sichere Ursache, ohne Netzwerk- oder Prüfwerte. */
class BcZeitstempelFehlermeldungenTest {

    private static final String POLICY = "1.3.6.1.4.1.99999.1";
    private static final TsaTestPki PKI = TsaTestPki.neu("fehlermeldungen");
    private static final Duration HOECHSTALTER = Duration.ofDays(7);
    private static final byte[] IMPRINT = Sha256.von("synthetisches Manifest".getBytes(StandardCharsets.UTF_8));
    private static final String URL_SENTINEL = "SYNTHETIC-URL-SENTINEL";
    private static final String HASH_SENTINEL = "0123456789abcdef".repeat(4);
    private static final String ANTWORT_SENTINEL = "SYNTHETIC-RESPONSE-BODY";
    private static final BcZeitstempelAdapter.Allowlist ALLOWLIST =
            new BcZeitstempelAdapter.Allowlist(Set.of(POLICY), List.of(PKI.ca));

    @ParameterizedTest(name = "{0}")
    @EnumSource(Fehlerpfad.class)
    void meldungBleibtWertefrei(Fehlerpfad pfad, @TempDir Path tempDir) throws Exception {
        Throwable fehler = catchThrowable(() -> pfad.ausloesen(tempDir));

        assertThat(fehler).isInstanceOf(ZeitstempelFehler.class).hasMessage(pfad.meldung);
        String ausgabe = Ursachen.ketteMitMeldung(fehler);
        assertThat(ausgabe).contains("[meldung=\"" + pfad.meldung + "\"]")
                .doesNotContain(URL_SENTINEL, HASH_SENTINEL, ANTWORT_SENTINEL);
    }

    private enum Fehlerpfad {
        DIENST_NICHT_ERREICHBAR("Zeitstempel nicht erhalten oder ungültig") {
            @Override
            void ausloesen(Path ordner) throws Exception {
                URI url = nichtErreichbareUrl();
                adapter(ordner, url, null, null, Duration.ofSeconds(1)).stempeln("haupt", IMPRINT);
            }
        },
        ZEITUEBERSCHREITUNG("Zeitstempel nicht erhalten oder ungültig") {
            @Override
            void ausloesen(Path ordner) throws Exception {
                mitServer(200, ANTWORT_SENTINEL, Duration.ofMillis(300),
                        url -> adapter(ordner, url, null, null, Duration.ofMillis(50)).stempeln("haupt", IMPRINT));
            }
        },
        HTTP_FEHLER("Zeitstempel nicht erhalten oder ungültig") {
            @Override
            void ausloesen(Path ordner) throws Exception {
                mitServer(500, ANTWORT_SENTINEL, Duration.ZERO,
                        url -> adapter(ordner, url, null, null, Duration.ofSeconds(1)).stempeln("haupt", IMPRINT));
            }
        },
        UNGUELTIGE_ANTWORT("Zeitstempel-Antwort ist kein gültiges RFC-3161-Format") {
            @Override
            void ausloesen(Path ordner) throws Exception {
                mitServer(200, ANTWORT_SENTINEL + HASH_SENTINEL, Duration.ZERO,
                        url -> adapter(ordner, url, null, null, Duration.ofSeconds(1)).stempeln("haupt", IMPRINT));
            }
        },
        FALSCHE_POLICY("Policy des Zeitstempels ist nicht erlaubt") {
            @Override
            void ausloesen(Path ordner) throws Exception {
                BcZeitstempelAdapter.geprueft("haupt", ALLOWLIST, tsr("1.3.6.1.4.1.99999.2"), IMPRINT, List.of());
            }
        },
        IMPRINT_ABWEICHUNG("Zeitstempel gehört nicht zu diesem Manifest") {
            @Override
            void ausloesen(Path ordner) throws Exception {
                BcZeitstempelAdapter.geprueft("haupt", ALLOWLIST, tsr(POLICY), new byte[32], List.of());
            }
        },
        PRUEFDATEN_ZU_ALT("Prüfdaten sind zu alt für den Zeitstempel") {
            @Override
            void ausloesen(Path ordner) {
                Instant genTime = Instant.now();
                BcZeitstempelAdapter.aktuell(Date.from(genTime.minus(Duration.ofDays(8))), null, genTime, HOECHSTALTER);
            }
        },
        OCSP_NICHT_ERREICHBAR("Prüfdaten nicht erhalten") {
            @Override
            void ausloesen(Path ordner) throws Exception {
                URI url = nichtErreichbareUrl();
                adapter(ordner, url, url, null, Duration.ofSeconds(1)).pruefdaten("haupt", stempel());
            }
        },
        CRL_NICHT_ERREICHBAR("Prüfdaten nicht erhalten") {
            @Override
            void ausloesen(Path ordner) throws Exception {
                URI url = nichtErreichbareUrl();
                adapter(ordner, url, null, url, Duration.ofSeconds(1)).pruefdaten("haupt", stempel());
            }
        },
        OCSP_GESPERRT("TSA-Zertifikat ist laut OCSP gesperrt oder unbekannt") {
            @Override
            void ausloesen(Path ordner) throws Exception {
                BcZeitstempelAdapter.ocspPruefen(PKI.ocsp(ocspAnfrage(), true), PKI.tsa, PKI.ca, Instant.now(),
                        HOECHSTALTER);
            }
        },
        CRL_GESPERRT("TSA-Zertifikat ist laut Sperrliste gesperrt") {
            @Override
            void ausloesen(Path ordner) {
                BcZeitstempelAdapter.crlPruefen(PKI.crl(true), PKI.tsa, PKI.ca, Instant.now(), HOECHSTALTER);
            }
        };

        private final String meldung;

        Fehlerpfad(String meldung) {
            this.meldung = meldung;
        }

        abstract void ausloesen(Path ordner) throws Exception;
    }

    private static BcZeitstempelAdapter adapter(Path ordner, URI url, URI ocsp, URI crl, Duration zeitgrenze)
            throws IOException {
        Path ca = Files.writeString(ordner.resolve("ca.pem"), PKI.caPem());
        Map<String, String> werte = new HashMap<>();
        werte.put("archiv.tsa.dienste", "haupt");
        String p = "archiv.tsa.dienst.haupt.";
        werte.put(p + "url", url.toString());
        werte.put(p + "policies", POLICY);
        werte.put(p + "ca", ca.toAbsolutePath().toString());
        if (ocsp != null) {
            werte.put(p + "ocsp-url", ocsp.toString());
        }
        if (crl != null) {
            werte.put(p + "crl-url", crl.toString());
        }
        if (ocsp == null && crl == null) {
            werte.put(p + "ocsp-url", "http://127.0.0.1:9/ocsp");
        }
        Config config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(Map.copyOf(werte), "test"))
                .build();
        return new BcZeitstempelAdapter(config, zeitgrenze, HOECHSTALTER);
    }

    private static void mitServer(int status, String antwort, Duration verzoegerung, Aktion aktion) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", austausch -> {
            try {
                Thread.sleep(verzoegerung.toMillis());
                byte[] bytes = antwort.getBytes(StandardCharsets.UTF_8);
                austausch.sendResponseHeaders(status, bytes.length);
                austausch.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                austausch.close();
            }
        });
        server.start();
        try {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"
                    + URL_SENTINEL + "?sha256=" + HASH_SENTINEL);
            aktion.ausfuehren(url);
        } finally {
            server.stop(0);
        }
    }

    private static URI nichtErreichbareUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/" + URL_SENTINEL
                    + "?sha256=" + HASH_SENTINEL);
        }
    }

    private static byte[] tsr(String policy) throws Exception {
        TimeStampRequestGenerator generator = new TimeStampRequestGenerator();
        generator.setCertReq(true);
        byte[] anfrage = generator.generate(TSPAlgorithms.SHA256, IMPRINT, BigInteger.TEN).getEncoded();
        return PKI.tsr(anfrage, policy);
    }

    private static Stempel stempel() throws Exception {
        return BcZeitstempelAdapter.geprueft("haupt", ALLOWLIST, tsr(POLICY), IMPRINT, List.of());
    }

    private static byte[] ocspAnfrage() throws Exception {
        CertificateID id = new CertificateID(new JcaDigestCalculatorProviderBuilder().build()
                .get(CertificateID.HASH_SHA1), new JcaX509CertificateHolder(PKI.ca), PKI.tsa.getSerialNumber());
        return new OCSPReqBuilder().addRequest(id).build().getEncoded();
    }

    @FunctionalInterface
    private interface Aktion {
        void ausfuehren(URI url) throws Exception;
    }
}
