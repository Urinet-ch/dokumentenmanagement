package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.dokumentart.application.DokumentZaehlerPort;
import jakarta.enterprise.context.ApplicationScoped;

/** Zählt für die Dokumentarten die Dokumente einer Art, verworfene eingeschlossen (Req 9.4). */
@ApplicationScoped
public class DokumentZaehler implements DokumentZaehlerPort {

    private final DokumentRepository repository;

    public DokumentZaehler(DokumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public long anzahl(String art) {
        return repository.anzahlMitArt(art);
    }
}
