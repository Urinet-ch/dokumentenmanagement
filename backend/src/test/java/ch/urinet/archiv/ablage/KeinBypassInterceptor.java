package ch.urinet.archiv.ablage;

import ch.urinet.archiv.ablage.adapter.out.s3.BypassVerbot;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Nachweis Req 6.5 (JUnit): hängt in den Tests am S3-Client der Anwendung ({@code %test.quarkus.s3.interceptors}) vor
 * {@link BypassVerbot}, hält jeden Aufruf mit dem Bypass-Header fest und lässt ihn nicht hinaus.
 */
public class KeinBypassInterceptor implements ExecutionInterceptor {

    static final String HEADER = BypassVerbot.HEADER;
    static final String SELBSTTEST = "bypass-selbsttest";
    private static final List<String> GESENDET = new CopyOnWriteArrayList<>();

    @Override
    public void beforeTransmission(Context.BeforeTransmission kontext, ExecutionAttributes attribute) {
        if (kontext.httpRequest().firstMatchingHeader(HEADER).isPresent()) {
            String pfad = kontext.httpRequest().encodedPath();
            GESENDET.add(pfad);
            throw new IllegalStateException("S3-Aufruf mit " + HEADER + " abgefangen: " + pfad);
        }
    }

    /** Pfade der abgefangenen Aufrufe ohne die des Selbsttests. */
    static List<String> gesendetAusserSelbsttest() {
        return GESENDET.stream().filter(p -> !p.contains(SELBSTTEST)).toList();
    }
}
