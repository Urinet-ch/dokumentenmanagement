package ch.urinet.archiv.protokoll.domain;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

/**
 * Ed25519-Signatur des kanonischen Manifests ({@code .sig}, Req 15.2) und der Hash für die Zeitstempel: SHA-256 von
 * Manifest und Signatur (Req 15.3, 15.11). Ed25519 ist deterministisch; derselbe Inhalt ergibt dieselbe Signatur, darum
 * bleibt ein wiederholter Lauf idempotent.
 */
public final class ManifestSignatur {

    public static final String ALGORITHMUS = "Ed25519";

    private ManifestSignatur() {
    }

    public static byte[] signieren(PrivateKey schluessel, byte[] manifest) {
        try {
            Signature s = Signature.getInstance(ALGORITHMUS);
            s.initSign(schluessel);
            s.update(manifest);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Manifest konnte nicht signiert werden", e);
        }
    }

    public static boolean pruefen(PublicKey schluessel, byte[] manifest, byte[] signatur) {
        try {
            Signature s = Signature.getInstance(ALGORITHMUS);
            s.initVerify(schluessel);
            s.update(manifest);
            return s.verify(signatur);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** Hash, den jeder Zeitstempeldienst erhält (nur dieser, Req 16.7). */
    public static byte[] imprint(byte[] manifest, byte[] signatur) {
        return Sha256.von(manifest, signatur);
    }
}
