package ch.urinet.archiv.protokoll.adapter.out.schluessel;

import ch.urinet.archiv.protokoll.application.ManifestSchluessel;
import ch.urinet.archiv.protokoll.domain.ManifestSignatur;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.eclipse.microprofile.config.Config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

/**
 * Ed25519-Schlüssel des Manifests (Req 15.2, 16.9): {@code archiv.manifest.schluessel} ist der Inhalt der Secret-Datei
 * {@code /run/secrets/archiv_manifest_key} (PKCS#8-PEM, erzeugt mit {@code openssl genpkey -algorithm ed25519}).
 * Der öffentliche Schlüssel (SPKI-PEM wie unter {@code docs/}) kommt aus {@code archiv.manifest.oeffentlicher-schluessel}
 * oder, wenn der Wert fehlt, aus der Datei {@code archiv.manifest.oeffentlicher-schluessel-datei}, sonst aus
 * {@code docs/manifest-schluessel.pub.pem}, das der Build als {@value #RESSOURCE} in das Artefakt kopiert; er wird nie
 * aus dem privaten abgeleitet, damit das Prüfwerkzeug ohne den
 * privaten Schlüssel läuft und nicht mit dem Schlüssel prüft, der auf dem Server liegt. Ist ein öffentlicher Schlüssel
 * vorhanden, muss der private dazu passen, sonst bricht das Signieren ab. Fehlermeldungen nennen nie den Schlüssel.
 */
@ApplicationScoped
public class DateiManifestSchluessel implements ManifestSchluessel {

    static final String PRIVAT = "archiv.manifest.schluessel";
    static final String OEFFENTLICH = "archiv.manifest.oeffentlicher-schluessel";
    static final String OEFFENTLICH_DATEI = "archiv.manifest.oeffentlicher-schluessel-datei";
    static final String RESSOURCE = "/manifest/manifest-schluessel.pub.pem";

    private final Config config;
    private final String ressource;
    private volatile PrivateKey privat;
    private volatile PublicKey oeffentlich;

    @Inject
    public DateiManifestSchluessel(Config config) {
        this(config, RESSOURCE);
    }

    DateiManifestSchluessel(Config config, String ressource) {
        this.config = config;
        this.ressource = ressource;
    }

    @Override
    public PrivateKey privat() {
        PrivateKey p = privat;
        if (p == null) {
            synchronized (this) {
                if (privat == null) {
                    privat = laden(config.getOptionalValue(PRIVAT, String.class), oeffentlicherPem());
                }
                p = privat;
            }
        }
        return p;
    }

    @Override
    public PublicKey oeffentlich() {
        PublicKey o = oeffentlich;
        if (o == null) {
            synchronized (this) {
                if (oeffentlich == null) {
                    oeffentlich = oeffentlich(oeffentlicherPem().orElseThrow(() -> new IllegalStateException(
                            "Öffentlicher Manifest-Schlüssel fehlt (" + OEFFENTLICH + ", " + OEFFENTLICH_DATEI
                                    + " oder docs/manifest-schluessel.pub.pem im Build)")));
                }
                o = oeffentlich;
            }
        }
        return o;
    }

    private Optional<String> oeffentlicherPem() {
        Optional<String> wert = config.getOptionalValue(OEFFENTLICH, String.class).filter(w -> !w.isBlank());
        if (wert.isPresent()) {
            return wert;
        }
        Optional<String> datei = config.getOptionalValue(OEFFENTLICH_DATEI, String.class).filter(w -> !w.isBlank());
        try {
            if (datei.isPresent()) {
                return Optional.of(Files.readString(Path.of(datei.get()), StandardCharsets.US_ASCII));
            }
            try (InputStream ein = DateiManifestSchluessel.class.getResourceAsStream(ressource)) {
                return ein == null ? Optional.empty()
                        : Optional.of(new String(ein.readAllBytes(), StandardCharsets.US_ASCII));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Öffentlicher Manifest-Schlüssel nicht lesbar ("
                    + datei.map(d -> OEFFENTLICH_DATEI).orElse(ressource) + ")", e);
        }
    }

    /** Privater Schlüssel; ist ein öffentlicher vorhanden, muss er dazu passen. */
    static PrivateKey laden(Optional<String> privatPem, Optional<String> oeffentlichPem) {
        if (privatPem.isEmpty() || privatPem.get().isBlank()) {
            throw new IllegalStateException("Manifest-Schlüssel fehlt (" + PRIVAT + ", Secret-Datei archiv_manifest_key)");
        }
        PrivateKey privat;
        try {
            byte[] pkcs8 = pem(privatPem.get(), "PRIVATE KEY");
            privat = KeyFactory.getInstance(ManifestSignatur.ALGORITHMUS).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            if (!(PrivateKeyFactory.createKey(pkcs8) instanceof Ed25519PrivateKeyParameters)) {
                throw new IllegalStateException("Manifest-Schlüssel ist kein Ed25519-Schlüssel");
            }
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Manifest-Schlüssel ist ungültig (erwartet PKCS#8-PEM, Ed25519)", e);
        }
        if (oeffentlichPem.isPresent() && !oeffentlichPem.get().isBlank()) {
            byte[] probe = "manifest-schluessel".getBytes(StandardCharsets.US_ASCII);
            if (!ManifestSignatur.pruefen(oeffentlich(oeffentlichPem.get()), probe,
                    ManifestSignatur.signieren(privat, probe))) {
                throw new IllegalStateException("Öffentlicher Manifest-Schlüssel passt nicht zum privaten");
            }
        }
        return privat;
    }

    static PublicKey oeffentlich(String pem) {
        try {
            return KeyFactory.getInstance(ManifestSignatur.ALGORITHMUS)
                    .generatePublic(new X509EncodedKeySpec(pem(pem, "PUBLIC KEY")));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Öffentlicher Manifest-Schlüssel ist ungültig (erwartet SPKI-PEM, Ed25519)", e);
        }
    }

    static byte[] pem(String text, String typ) {
        String anfang = "-----BEGIN " + typ + "-----";
        String ende = "-----END " + typ + "-----";
        int a = text.indexOf(anfang);
        int e = text.indexOf(ende);
        if (a < 0 || e < a) {
            throw new IllegalArgumentException("Kein PEM-Block " + typ);
        }
        return Base64.getMimeDecoder().decode(text.substring(a + anfang.length(), e));
    }
}
