package ch.urinet.archiv.sicherheit.application;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Erklärung einer Ressourcen-Methode ohne Anmeldung als Archiv-Benutzer (z. B. öffentliche Schnittstelle, Spec 03).
 * Der Filter prüft weder Mandant noch Rolle; die Methode trägt {@code @PermitAll} und kein {@code @RolesAllowed}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Oeffentlich {
}
