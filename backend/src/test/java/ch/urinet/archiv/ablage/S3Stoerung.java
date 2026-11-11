package ch.urinet.archiv.ablage;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simulierte Störung des Object Storage, nur in Tests ({@code %test.quarkus.s3.interceptors}): Nach
 * {@link #ab(int, Art)} gehen so viele Uploads unter {@code eingang/} durch, danach scheitert jeder weitere – als
 * Netzfehler oder als Antwort 503 nach dem Speichern (die Datei liegt dann trotzdem im Bucket).
 */
public class S3Stoerung implements ExecutionInterceptor {

    /** Art der Störung. */
    public enum Art {
        NETZ, ANTWORT_503
    }

    private static final AtomicReference<Art> ART = new AtomicReference<>();
    private static final AtomicInteger FREI = new AtomicInteger();

    /** Lässt {@code erlaubt} Uploads unter {@code eingang/} durch, danach scheitert jeder weitere. */
    public static void ab(int erlaubt, Art art) {
        FREI.set(erlaubt);
        ART.set(art);
    }

    public static void aus() {
        ART.set(null);
    }

    @Override
    public void beforeExecution(Context.BeforeExecution kontext, ExecutionAttributes attribute) {
        if (betroffen(kontext.request()) && ART.get() == Art.NETZ && FREI.getAndDecrement() <= 0) {
            throw SdkClientException.create("Object Storage nicht erreichbar (Test)");
        }
    }

    @Override
    public SdkHttpResponse modifyHttpResponse(Context.ModifyHttpResponse kontext, ExecutionAttributes attribute) {
        if (betroffen(kontext.request()) && ART.get() == Art.ANTWORT_503 && FREI.getAndDecrement() <= 0) {
            return kontext.httpResponse().toBuilder().statusCode(503).statusText("Service Unavailable").build();
        }
        return kontext.httpResponse();
    }

    private static boolean betroffen(Object anfrage) {
        return ART.get() != null && anfrage instanceof PutObjectRequest put && put.key().startsWith("eingang/");
    }
}
