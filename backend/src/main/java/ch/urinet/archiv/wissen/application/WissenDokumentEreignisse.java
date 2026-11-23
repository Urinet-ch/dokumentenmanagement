package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.dokument.application.DokumentEreignisse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Set;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class WissenDokumentEreignisse implements DokumentEreignisse {
    private final QuelldokumentBeobachter beobachter;

    public WissenDokumentEreignisse(QuelldokumentBeobachter beobachter) {
        this.beobachter = beobachter;
    }

    @Override
    public void archiviert(UUID dokumentId, String art) {
        // Der Ingest beginnt erst mit der ausdrücklichen Markierung des archivierten Dokuments.
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
