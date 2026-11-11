package ch.urinet.archiv.ablage.adapter.out.s3;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Lehnt jeden S3-Aufruf mit dem Bypass-Header ab, bevor er den Rechner verlässt (Req 6.5, tech.md Regel 2), in allen
 * Profilen ({@code quarkus.s3.interceptors}). Fail closed: {@link #pruefen(S3Client)} lässt keinen S3-Client ohne
 * diesen Interceptor zu.
 */
public class BypassVerbot implements ExecutionInterceptor {

    /** Einzige erlaubte Stelle des Header-Texts in {@code src/main} ({@code KeinBypassHeaderTest}). */
    public static final String HEADER = "x-amz-bypass-governance-retention";

    @Override
    public void beforeTransmission(Context.BeforeTransmission kontext, ExecutionAttributes attribute) {
        if (kontext.httpRequest().firstMatchingHeader(HEADER).isPresent()) {
            throw new IllegalStateException("S3-Aufruf mit " + HEADER + " abgelehnt: " + kontext.httpRequest().encodedPath());
        }
    }

    /** Gibt den Client zurück, wenn {@link BypassVerbot} an ihm hängt; sonst Abbruch. */
    public static S3Client pruefen(S3Client s3) {
        boolean vorhanden = s3.serviceClientConfiguration().overrideConfiguration().executionInterceptors().stream()
                .anyMatch(BypassVerbot.class::isInstance);
        if (!vorhanden) {
            throw new IllegalStateException("S3-Client ohne " + BypassVerbot.class.getName()
                    + " (quarkus.s3.interceptors, Req 6.5)");
        }
        return s3;
    }
}
