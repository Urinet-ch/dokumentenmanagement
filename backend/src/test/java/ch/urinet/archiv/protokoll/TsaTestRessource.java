package ch.urinet.archiv.protokoll;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Zwei synthetische Zeitstempeldienste für die Szenarien (Aufgabe 5.2; Auftrag: TSA in Tests nur über WireMock):
 * {@code haupt} mit OCSP, {@code zweit} mit Sperrliste. Antworten entstehen aus der Anfrage ({@link TsaTestPki}).
 * Störungen setzen die Schritte als Stub mit höherer Priorität über die Admin-Schnittstelle
 * ({@code archiv.test.tsa-port}). Alle Werte nur im Profil {@code test}, damit Prod-Tests ihre eigene Konfiguration
 * behalten. Dazu ein synthetisches Ed25519-Paar als Manifest-Schlüssel.
 */
public class TsaTestRessource implements QuarkusTestResourceLifecycleManager {

    public static final String POLICY = "1.3.6.1.4.1.99999.1.1";
    static final String TRANSFORMER = "tsa-test";

    private WireMockServer server;

    @Override
    public Map<String, String> start() {
        TsaTestPki haupt = TsaTestPki.neu("haupt");
        TsaTestPki zweit = TsaTestPki.neu("zweit");
        server = new WireMockServer(wireMockConfig().dynamicPort().extensions(new Antworten(haupt, zweit)));
        server.start();
        server.stubFor(any(urlPathMatching("/(haupt|zweit)/.*")).atPriority(10)
                .willReturn(ResponseDefinitionBuilder.responseDefinition().withTransformers(TRANSFORMER)));
        String basis = "http://localhost:" + server.port();
        String[] ed25519 = TsaTestPki.ed25519Pem();
        Map<String, String> werte = new HashMap<>();
        werte.put("archiv.test.tsa-port", String.valueOf(server.port()));
        werte.put("archiv.tsa.dienste", "haupt,zweit");
        werte.put("archiv.tsa.zeitgrenze", "PT5S");
        werte.put("archiv.tsa.dienst.haupt.url", basis + "/haupt/tsr");
        werte.put("archiv.tsa.dienst.haupt.policies", POLICY);
        werte.put("archiv.tsa.dienst.haupt.ca", datei("haupt", haupt.caPem()));
        werte.put("archiv.tsa.dienst.haupt.ocsp-url", basis + "/haupt/ocsp");
        werte.put("archiv.tsa.dienst.zweit.url", basis + "/zweit/tsr");
        werte.put("archiv.tsa.dienst.zweit.policies", POLICY);
        werte.put("archiv.tsa.dienst.zweit.ca", datei("zweit", zweit.caPem()));
        werte.put("archiv.tsa.dienst.zweit.crl-url", basis + "/zweit/crl");
        werte.put("archiv.manifest.schluessel", ed25519[0]);
        werte.put("archiv.manifest.oeffentlicher-schluessel", ed25519[1]);
        Map<String, String> test = new HashMap<>();
        werte.forEach((k, v) -> test.put("%test." + k, v));
        return test;
    }

    private static String datei(String name, String pem) {
        try {
            Path ordner = Files.createDirectories(Path.of("target", "tsa-test").toAbsolutePath());
            Path ziel = ordner.resolve(name + "-ca.pem");
            Files.writeString(ziel, pem, StandardCharsets.US_ASCII);
            return ziel.toString().replace('\\', '/');
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    /** Erzeugt TSR, OCSP und CRL je Dienst aus der Anfrage. */
    static final class Antworten implements ResponseDefinitionTransformerV2 {

        private final TsaTestPki haupt;
        private final TsaTestPki zweit;

        Antworten(TsaTestPki haupt, TsaTestPki zweit) {
            this.haupt = haupt;
            this.zweit = zweit;
        }

        @Override
        public ResponseDefinition transform(ServeEvent ereignis) {
            String pfad = ereignis.getRequest().getUrl();
            TsaTestPki pki = pfad.startsWith("/haupt/") ? haupt : zweit;
            byte[] koerper;
            String typ;
            if (pfad.endsWith("/tsr")) {
                koerper = pki.tsr(ereignis.getRequest().getBody(), POLICY);
                typ = "application/timestamp-reply";
            } else if (pfad.endsWith("/ocsp")) {
                koerper = pki.ocsp(ereignis.getRequest().getBody(), false);
                typ = "application/ocsp-response";
            } else if (pfad.endsWith("/crl")) {
                koerper = pki.crl(false);
                typ = "application/pkix-crl";
            } else {
                return ResponseDefinitionBuilder.responseDefinition().withStatus(404).build();
            }
            return ResponseDefinitionBuilder.responseDefinition().withStatus(200).withHeader("Content-Type", typ)
                    .withBody(koerper).build();
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public String getName() {
            return TRANSFORMER;
        }
    }
}
