package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ki.application.ChunkPort;
import ch.urinet.archiv.ki.application.EmbeddingService;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.ki.domain.Chunker;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;

@ApplicationScoped
public class WissensEmbeddingHandler implements AuftragHandler {
    private static final Logger LOG = Logger.getLogger(WissensEmbeddingHandler.class);
    private final WissensEmbeddingSpeichern speichern;
    private final EmbeddingService embedding;
    private final WissenModellBudget budget;

    public WissensEmbeddingHandler(WissensEmbeddingSpeichern speichern, EmbeddingService embedding,
                                   WissenModellBudget budget) {
        this.speichern = speichern;
        this.embedding = embedding;
        this.budget = budget;
    }

    @Override
    public AuftragTyp typ() { return WissensEmbeddingSpeichern.EINZELN; }

    @Override
    public void ausfuehren(Auftrag auftrag) throws InterruptedException {
        var id = auftrag.nutzlast().uuid("seiteId")
                .orElseThrow(() -> new IllegalArgumentException("Wissensembedding ohne seiteId"));
        int version = Math.toIntExact(auftrag.nutzlast().zahl("version")
                .orElseThrow(() -> new IllegalArgumentException("Wissensembedding ohne version")));
        var stand = speichern.laden(id);
        if (stand.isEmpty() || stand.get().version() != version) {
            LOG.infof("Wissensembedding übersprungen: Seite %s, Version %d überholt", id, version);
            return;
        }
        var chunks = Chunker.zerlegen(stand.get().quelle());
        var vektoren = embedding.einbetten(id, chunks, KiVerbrauchPort.WISSEN_EMBEDDING, budget::embeddingPruefen);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Wissensembedding unterbrochen");
        var eingebettet = new ArrayList<ChunkPort.Eingebettet>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            eingebettet.add(new ChunkPort.Eingebettet(chunks.get(i), vektoren.isPresent() ? vektoren.get().get(i) : null));
        }
        boolean ersetzt = speichern.ersetzen(auftrag, id, version, eingebettet);
        LOG.infof("Wissensembedding Seite %s, Version %d: ersetzt=%s", id, version, ersetzt);
    }
}
