package ch.urinet.archiv.ablage.domain;

import java.time.Instant;
import java.util.Optional;

/** Ergebnis einer Ablage: Objektschlüssel, S3-Versions-ID, SHA-256 (hex), Grösse und Sperre (leer = keine). */
public record AbgelegteDatei(String schluessel, String versionId, String sha256, long groesse,
                             Optional<Instant> sperreBis) {
}
