package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.sicherheit.domain.Benutzer;

import java.util.UUID;

/**
 * Driven Port: Ist das Dokument im Mandanten des Benutzers vorhanden und für ihn nach A2–A4 sichtbar? Umsetzung mit
 * dem {@code VertraulichkeitFilter} (Aufgabe 2.2); ein Dokument eines anderen Mandanten ist nie sichtbar.
 */
public interface Sichtbarkeit {

    boolean sichtbar(Benutzer benutzer, UUID dokumentId);
}
