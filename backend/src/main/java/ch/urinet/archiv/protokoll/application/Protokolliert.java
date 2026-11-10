package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Protokolliert den Aufruf einer Resource-Methode synchron in derselben Transaktion wie die Aktion (Req 10.2, 10.8):
 * Der Eintrag entsteht vor der Aktion; scheitert er, wird die Aktion nicht ausgeführt bzw. zurückgerollt (500). Die
 * Dokument-ID kommt aus dem Pfad-Parameter {@code id}. {@code EndpunktWaechterTest} verlangt die Annotation an jedem
 * Endpunkt der Tabelle in design.md «Protokoll und Manifest».
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Protokolliert {

    @Nonbinding
    ProtokollAktion aktion();
}
