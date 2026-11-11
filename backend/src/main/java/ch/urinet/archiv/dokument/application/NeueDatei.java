package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.ablage.domain.DateiRolle;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Zeile in {@code dokument_datei} (Req 3.4, 6.1): SHA-256 und Empfangszeit, bevor die Datei abgelegt wird. */
public record NeueDatei(UUID dokumentId, DateiRolle rolle, int nr, String sha256, String s3Schluessel, String mime,
                        long groesse, Instant empfangen) {

    public NeueDatei {
        Objects.requireNonNull(dokumentId, "dokumentId");
        Objects.requireNonNull(rolle, "rolle");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(s3Schluessel, "s3Schluessel");
        Objects.requireNonNull(mime, "mime");
        Objects.requireNonNull(empfangen, "empfangen");
    }
}
