package ch.urinet.archiv.sicherheit.adapter.out.sichtbarkeit;

import ch.urinet.archiv.sicherheit.application.Sichtbarkeit;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

/**
 * Platzhalter bis zum {@code VertraulichkeitFilter} (Aufgabe 2.2): Es gibt noch keine Dokumente, darum ist keines
 * sichtbar (fail closed, jede Dokument-ID antwortet 404).
 */
@ApplicationScoped
public class OhneDokumenteSichtbarkeit implements Sichtbarkeit {

    @Override
    public boolean sichtbar(Benutzer benutzer, UUID dokumentId) {
        return false;
    }
}
