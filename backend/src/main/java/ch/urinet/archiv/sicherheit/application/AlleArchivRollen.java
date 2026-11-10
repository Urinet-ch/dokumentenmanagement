package ch.urinet.archiv.sicherheit.application;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Erklärung einer Ressourcen-Methode ohne eigene Matrixzeile: jede Archiv-Rolle des Mandanten (z. B. Stammdaten
 * lesen). Mandant und Rolle prüft der Filter trotzdem; {@code @RolesAllowed} nennt alle vier Rollen.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AlleArchivRollen {
}
