package ch.urinet.archiv.ablage;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Langsamer Object Storage, nur in Tests ({@code %test.quarkus.s3.interceptors}): Nach {@link #anhalten(String)}
 * bleibt jedes Auflisten von Versionen, dessen Präfix den Text enthält, stehen, bis der Test {@link #freigeben()} ruft
 * (höchstens 60 s).
 */
public class S3Verzoegerung implements ExecutionInterceptor {

    private record Halt(String text, CountDownLatch erreicht, CountDownLatch frei) {
    }

    private static final AtomicReference<Halt> HALT = new AtomicReference<>();

    public static void anhalten(String text) {
        HALT.set(new Halt(text, new CountDownLatch(1), new CountDownLatch(1)));
    }

    /** Wartet, bis ein Aufruf angehalten ist. */
    public static boolean erreicht(long sekunden) throws InterruptedException {
        return HALT.get().erreicht().await(sekunden, TimeUnit.SECONDS);
    }

    public static void freigeben() {
        Halt halt = HALT.getAndSet(null);
        if (halt != null) {
            halt.frei().countDown();
        }
    }

    @Override
    public void beforeExecution(Context.BeforeExecution kontext, ExecutionAttributes attribute) {
        Halt halt = HALT.get();
        if (halt != null && kontext.request() instanceof ListObjectVersionsRequest liste && liste.prefix() != null
                && liste.prefix().contains(halt.text())) {
            halt.erreicht().countDown();
            try {
                halt.frei().await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
