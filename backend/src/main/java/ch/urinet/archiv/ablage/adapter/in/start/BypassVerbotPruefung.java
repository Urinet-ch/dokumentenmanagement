package ch.urinet.archiv.ablage.adapter.in.start;

import ch.urinet.archiv.ablage.adapter.out.s3.BypassVerbot;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.Interceptor;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Startprüfung zum Bypass-Verbot (Req 6.5): Hängt {@link BypassVerbot} nicht am S3-Client der Anwendung, bricht der
 * Start ab, nicht erst der erste Zugriff. Der Client ist derselbe für Ablage und Bucket-Einrichtung der Mandanten.
 */
@ApplicationScoped
public class BypassVerbotPruefung {

    void beimStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent ereignis,
                   Instance<S3Client> s3) {
        BypassVerbot.pruefen(s3.get());
    }
}
