package ch.urinet.archiv.protokoll.domain;

import java.util.List;
import java.util.Objects;

/**
 * Erneuerung (Req 15.2, RFC 4998): SHA-256 des {@code .tsr} eines Dienstes und seiner abgelegten Prüfdaten
 * (Zertifikatskette zuerst, dann OCSP- oder CRL-Antwort) im Manifest des Folgetags.
 */
public record ZeitstempelVerweis(String dienst, String tsrSha256, List<String> pruefdatenSha256) {

    public ZeitstempelVerweis {
        Objects.requireNonNull(dienst, "dienst");
        Objects.requireNonNull(tsrSha256, "tsrSha256");
        Sha256.gueltig(tsrSha256, "tsr_sha256");
        pruefdatenSha256 = List.copyOf(pruefdatenSha256);
        pruefdatenSha256.forEach(s -> Sha256.gueltig(s, "pruefdaten_sha256"));
    }
}
