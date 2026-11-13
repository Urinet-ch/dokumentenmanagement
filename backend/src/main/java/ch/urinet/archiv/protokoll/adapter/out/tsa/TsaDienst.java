package ch.urinet.archiv.protokoll.adapter.out.tsa;

import org.eclipse.microprofile.config.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Konfiguration eines Zeitstempeldienstes {@code archiv.tsa.dienst.<kennung>.*} (Req 15.3, 15.11; 4.9-E1): {@code url},
 * {@code policies} (erlaubte Policy-OIDs), {@code ca} (PEM der erlaubten ausstellenden CAs; beginnt der Wert mit
 * {@code /}, ist es eine Datei, sonst eine Ressource im Classpath), dazu {@code ocsp-url} oder {@code crl-url} und
 * optional {@code pruefdaten-hoechstalter} (sonst {@code archiv.tsa.pruefdaten-hoechstalter}).
 */
record TsaDienst(String kennung, URI url, Set<String> policies, List<X509Certificate> cas, Optional<URI> ocsp,
                 Optional<URI> crl, Optional<Duration> pruefdatenHoechstalter) {

    static final String DIENSTE = "archiv.tsa.dienste";
    private static final Pattern OID = Pattern.compile("[0-2](\\.\\d+)+");

    static List<String> kennungen(Config config) {
        return config.getOptionalValues(DIENSTE, String.class).orElse(List.of()).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** Ob ein Block {@code archiv.tsa.dienst.<kennung>.*} konfiguriert ist (auch für ausgetragene Dienste). */
    static boolean vorhanden(Config config, String kennung) {
        String p = "archiv.tsa.dienst." + kennung + ".";
        return Stream.of("url", "policies", "ca", "ocsp-url", "crl-url", "pruefdaten-hoechstalter")
                .anyMatch(s -> config.getOptionalValue(p + s, String.class).isPresent());
    }

    static TsaDienst laden(Config config, String kennung) {
        String p = "archiv.tsa.dienst." + kennung + ".";
        URI url = uri(config.getOptionalValue(p + "url", String.class)
                .orElseThrow(() -> fehlt(p + "url")), p + "url");
        Set<String> policies = new LinkedHashSet<>();
        for (String oid : config.getOptionalValues(p + "policies", String.class).orElseThrow(() -> fehlt(p + "policies"))) {
            if (!OID.matcher(oid.trim()).matches()) {
                throw new IllegalStateException(p + "policies enthält keine gültige OID");
            }
            policies.add(oid.trim());
        }
        List<X509Certificate> cas = zertifikate(config.getOptionalValue(p + "ca", String.class)
                .orElseThrow(() -> fehlt(p + "ca")), p + "ca");
        Optional<URI> ocsp = config.getOptionalValue(p + "ocsp-url", String.class).map(u -> uri(u, p + "ocsp-url"));
        Optional<URI> crl = config.getOptionalValue(p + "crl-url", String.class).map(u -> uri(u, p + "crl-url"));
        if (ocsp.isEmpty() && crl.isEmpty()) {
            throw new IllegalStateException(p + "ocsp-url oder " + p + "crl-url fehlt (Prüfdaten, Req 15.11)");
        }
        Optional<Duration> hoechstalter = config.getOptionalValue(p + "pruefdaten-hoechstalter", String.class)
                .map(w -> dauer(w, p + "pruefdaten-hoechstalter"));
        return new TsaDienst(kennung, url, Set.copyOf(policies), List.copyOf(cas), ocsp, crl, hoechstalter);
    }

    private static Duration dauer(String wert, String schluessel) {
        try {
            Duration d = Duration.parse(wert.trim());
            if (d.isNegative() || d.isZero()) {
                throw new IllegalStateException(schluessel + " muss grösser als 0 sein");
            }
            return d;
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(schluessel + " ist keine Dauer (ISO 8601, z. B. P7D)", e);
        }
    }

    private static IllegalStateException fehlt(String schluessel) {
        return new IllegalStateException("Konfiguration fehlt: " + schluessel);
    }

    private static URI uri(String wert, String schluessel) {
        try {
            URI uri = URI.create(wert.trim());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalStateException(schluessel + " ist keine HTTP(S)-Adresse");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(schluessel + " ist keine gültige Adresse", e);
        }
    }

    static List<X509Certificate> zertifikate(String ort, String schluessel) {
        try (InputStream ein = oeffnen(ort.trim())) {
            if (ein == null) {
                throw new IllegalStateException(schluessel + ": Datei nicht gefunden");
            }
            return pem(new String(ein.readNBytes(1024 * 1024), StandardCharsets.US_ASCII));
        } catch (IOException | CertificateException e) {
            throw new IllegalStateException(schluessel + ": Zertifikate nicht lesbar", e);
        }
    }

    private static InputStream oeffnen(String ort) throws IOException {
        if (ort.startsWith("/")) {
            return Files.newInputStream(Path.of(ort));
        }
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(ort);
    }

    static List<X509Certificate> pem(String text) throws CertificateException {
        List<X509Certificate> liste = new ArrayList<>();
        CertificateFactory fabrik = CertificateFactory.getInstance("X.509");
        for (var z : fabrik.generateCertificates(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)))) {
            liste.add((X509Certificate) z);
        }
        if (liste.isEmpty()) {
            throw new CertificateException("keine Zertifikate");
        }
        return liste;
    }
}
