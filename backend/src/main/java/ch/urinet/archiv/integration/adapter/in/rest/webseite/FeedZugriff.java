package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Kennzeichnet den Lese-Feed (Spec 03, Aufgabe 11.9, Req 14.1): Dort gilt die Stufe des Schlüssels, nicht die
 * gemeldete Stufe der Anfrage. {@link LeseZugriffFilter} liest für solche Endpunkte weder {@code X-Archiv-Zugriff} noch
 * {@code X-Archiv-Client} (ein falscher Wert dort ist kein Fehler des Feeds) und setzt die Stufe des Schlüssels.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface FeedZugriff {
}
