package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.dokument.application.DokumentEreignisse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Set;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class WissenDokumentEreignisse implements DokumentEreignisse {
    private final WissenIngestStarten ingest;
    private final QuelldokumentBeobachter beobachter;

    public WissenDokumentEreignisse(WissenIngestStarten ingest, QuelldokumentBeobachter beobachter) {
        this.ingest = ingest;
        this.beobachter = beobachter;
    }

    @Override
    public void archiviert(UUID dokumentId, String art) {
        ingest.archiviert(dokumentId, art);
    }

    @Override
    public void vorAenderung() {
        beobachter.sperren();
    }

    @Override
    public void geaendert(UUID dokumentId, Set<String> felder) {
        beobachter.geaendert(dokumentId);
    }
}
