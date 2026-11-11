package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.UUID;

/**
 * Dokument und Eingangskorb lesen (A2–A5, Req 2.3, 2.21, 2.24). Ein unsichtbares oder fremdes Dokument gilt als nicht
 * vorhanden (404, Req 1.4); die REST-Schicht prüft das Recht vorher, hier filtert die {@link Sichtregel} nochmals.
 */
@ApplicationScoped
@Transactional
public class DokumentLesen {

    static final int GROESSE_MAX = 100;

    private final DokumentRepository repository;

    public DokumentLesen(DokumentRepository repository) {
        this.repository = repository;
    }

    public Dokument lesen(Benutzer benutzer, UUID id) {
        return repository.finden(id, Sichtregel.fuer(benutzer))
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
    }

    /** Eingangskorb, neueste zuerst; {@code seite} ab 0, {@code groesse} 1–{@value #GROESSE_MAX}, sonst 400. */
    public Seite<Dokument> eingangskorb(Benutzer benutzer, int seite, int groesse) {
        if (seite < 0 || groesse < 1 || groesse > GROESSE_MAX) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return repository.eingangskorb(Sichtregel.fuer(benutzer), seite, groesse);
    }
}
