package ch.urinet.archiv.protokoll.application;

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * Driven Port: Ed25519-Schlüssel des Manifests (Req 15.2, 16.9). Der private Schlüssel kommt nur aus der Secret-Datei
 * {@code archiv_manifest_key}; fehlt er, scheitert erst der Auftrag (Alarm nach Req 15.8), nicht der Start.
 */
public interface ManifestSchluessel {

    PrivateKey privat();

    /**
     * Öffentlicher Schlüssel zum Prüfen, nur aus der Konfiguration bzw. der Datei wie unter {@code docs/}; nie aus dem
     * privaten abgeleitet, damit das Prüfwerkzeug ohne den privaten Schlüssel läuft. Fehlt er, bricht der Aufruf ab.
     */
    PublicKey oeffentlich();
}
