package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.integration.domain.webseite.Grenzklasse;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stuft einen Endpunkt unter {@code /public/} strenger ein als sein Standard (Spec 03, 11.11): {@link Grenzklasse#SUCHE}
 * für die teure Suche, {@link Grenzklasse#UPLOAD} für Uploads. Ohne Annotation gilt {@code LESEN} bzw. {@code ERFASSUNG}
 * nach dem Scope von {@code @WebseitenZugang}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WebseitenGrenze {

    Grenzklasse value();
}
