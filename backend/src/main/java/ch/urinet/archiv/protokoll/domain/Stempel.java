package ch.urinet.archiv.protokoll.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Geprüfter RFC-3161-Zeitstempel eines Dienstes (Req 15.3, 15.11): die Antwort ({@code .tsr}, DER), ihre Zeit
 * ({@code genTime}) und die Zertifikatskette der TSA (PEM, TSA-Zertifikat zuerst, dann die ausstellende CA).
 */
public record Stempel(String dienst, byte[] tsr, Instant genTime, String kettePem) {

    public Stempel {
        Objects.requireNonNull(dienst, "dienst");
        Objects.requireNonNull(tsr, "tsr");
        Objects.requireNonNull(genTime, "genTime");
        Objects.requireNonNull(kettePem, "kettePem");
    }
}
