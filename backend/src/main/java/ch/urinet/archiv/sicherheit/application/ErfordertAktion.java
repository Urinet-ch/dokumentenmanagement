package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.sicherheit.domain.Aktion;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Erklärung einer Ressourcen-Methode: Sie führt diese Zeile der Rechtematrix aus. {@code @RolesAllowed} muss genau die
 * Rollen nennen, für die die Aktion nicht 403 ist ({@code EndpunktWaechterTest}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ErfordertAktion {
    Aktion value();
}
