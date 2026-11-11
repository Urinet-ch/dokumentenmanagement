package ch.urinet.archiv.dokument.application;

import java.util.UUID;

/**
 * Protokoll des Verwerfens (Req 6.6, design.md «Protokoll und Manifest»): je gelöschte Datei ein Eintrag
 * {@code VERWERFEN} mit SHA-256 und Grund, in der Transaktion des Aufrufers. Benutzer, Rolle und IP kommen aus der
 * Anfrage.
 */
public interface VerwerfenProtokollPort {

    void dateiVerworfen(UUID dokumentId, String sha256, String grund);
}
