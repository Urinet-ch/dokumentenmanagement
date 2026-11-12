package ch.urinet.archiv.protokoll.adapter.out.schluessel;

import ch.urinet.archiv.protokoll.application.ManifestSchluessel;
import ch.urinet.archiv.protokoll.domain.ManifestSignatur;
import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.eclipse.microprofile.config.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * {@code archiv.manifest.oeffentlicher-schluessel} (SPKI-PEM wie unter {@code docs/}) ist optional; ist er gesetzt,
 * muss er zum privaten passen, sonst bricht jede Nutzung ab. Fehlermeldungen nennen nie den Schlüssel.
 */
@ApplicationScoped
public class DateiManifestSchluessel implements ManifestSchluessel {

    static final String PRIVAT = "archiv.manifest.schluessel";
    static final String OEFFENTLICH = "archiv.manifest.oeffentlicher-schluessel";

    private final Config config;
    private volatile Paar paar;

    public DateiManifestSchluessel(Config config) {
        this.config = config;
    }

    @Override
    public PrivateKey privat() {
        return paar().privat();
    }

    @Override
    public PublicKey oeffentlich() {
        return paar().oeffentlich();
    }

    private Paar paar() {
        Paar p = paar;
        if (p == null) {
            synchronized (this) {
                if (paar == null) {
                    paar = laden(config.getOptionalValue(PRIVAT, String.class),
                            config.getOptionalValue(OEFFENTLICH, String.class));
                }
                p = paar;
            }
        }
        return p;
    }

    static Paar laden(Optional<String> privatPem, Optional<String> oeffentlichPem) {
        if (privatPem.isEmpty() || privatPem.get().isBlank()) {
            throw new IllegalStateException("Manifest-Schlüssel fehlt (" + PRIVAT + ", Secret-Datei archiv_manifest_key)");
        }
        try {
            byte[] pkcs8 = pem(privatPem.get(), "PRIVATE KEY");
            KeyFactory fabrik = KeyFactory.getInstance(ManifestSignatur.ALGORITHMUS);
            PrivateKey privat = fabrik.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            AsymmetricKeyParameter parameter = PrivateKeyFactory.createKey(pkcs8);
            if (!(parameter instanceof Ed25519PrivateKeyParameters ed)) {
                throw new IllegalStateException("Manifest-Schlüssel ist kein Ed25519-Schlüssel");
            }
            byte[] spki = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(ed.generatePublicKey()).getEncoded();
            PublicKey oeffentlich = fabrik.generatePublic(new X509EncodedKeySpec(spki));
            if (oeffentlichPem.isPresent() && !oeffentlichPem.get().isBlank()) {
                oeffentlich = fabrik.generatePublic(new X509EncodedKeySpec(pem(oeffentlichPem.get(), "PUBLIC KEY")));
                byte[] probe = "manifest-schluessel".getBytes(StandardCharsets.US_ASCII);
                if (!ManifestSignatur.pruefen(oeffentlich, probe, ManifestSignatur.signieren(privat, probe))) {
                    throw new IllegalStateException("Öffentlicher Manifest-Schlüssel passt nicht zum privaten");
                }
            }
            return new Paar(privat, oeffentlich);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Manifest-Schlüssel ist ungültig (erwartet PKCS#8-PEM, Ed25519)", e);
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

    record Paar(PrivateKey privat, PublicKey oeffentlich) {
    }
}
