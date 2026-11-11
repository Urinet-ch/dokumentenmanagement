package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.sicherheit.application.Sichtbarkeit;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

/**
 * Sichtbarkeit für die Rechteprüfung (404 vor 403, design.md «Sicherheit») nach der {@link Sichtregel}: im Schema des
 * Mandanten der Anfrage, darum ist ein Dokument eines anderen Mandanten nie sichtbar (Req 1.4).
 */
@ApplicationScoped
public class DokumentSichtbarkeit implements Sichtbarkeit {

    private final DokumentRepository repository;

    public DokumentSichtbarkeit(DokumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean sichtbar(Benutzer benutzer, UUID dokumentId) {
        return repository.sichtbar(dokumentId, Sichtregel.fuer(benutzer));
    }
}
