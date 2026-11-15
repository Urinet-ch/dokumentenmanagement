package ch.urinet.archiv.sicherheit.application;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Kennzeichnet einen Endpunkt, der ausschliesslich mit dem passenden Webseiten-Schlüssel erreichbar ist. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WebseitenZugang {

    Art value();

    enum Art {
        LESEN, ERFASSUNG
    }
}
