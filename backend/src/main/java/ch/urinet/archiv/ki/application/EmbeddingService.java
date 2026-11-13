package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.Chunk;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Embeddings der Abschnitte eines Dokuments (Spec 02, Req 2.1; design.md «Embeddings und Hybridsuche»): je
 * {@link EmbeddingPort#BATCH} Abschnitte ein Aufruf, jeder Aufruf mit Antwort als Zeile in {@code ki_verbrauch} (Zweck
 * {@link KiVerbrauchPort#EMBEDDING}). Nicht in einer Transaktion aufrufen (Netz).
 * <p>
 * Antwortet das Modell nicht passend, wirft der Dienst {@link EmbeddingAusfall}: Fehlversuch des Auftrags nach Spec 01,
 * Req 5.4. Liefert der Port keine Vektoren (Dev-Stub, fehlendes Token), entstehen die Abschnitte ohne Vektor; die
 * Nachberechnung (Req 2.4) holt sie nach. Das Log nennt nur IDs.
 */
@ApplicationScoped
public class EmbeddingService {

    private static final Logger LOG = Logger.getLogger(EmbeddingService.class);

    private final EmbeddingPort port;
    private final KiVerbrauchPort verbrauch;

    public EmbeddingService(EmbeddingPort port, KiVerbrauchPort verbrauch) {
        this.port = port;
        this.verbrauch = verbrauch;
    }

    /** Je Abschnitt ein Vektor in derselben Reihenfolge; leer ohne Vektoren (Stub, kein Token). */
    public Optional<List<float[]>> einbetten(UUID dokumentId, List<Chunk> chunks) throws InterruptedException {
        List<float[]> vektoren = new ArrayList<>(chunks.size());
        for (int start = 0; start < chunks.size(); start += EmbeddingPort.BATCH) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Embeddings unterbrochen");
            }
            List<String> texte = chunks.subList(start, Math.min(chunks.size(), start + EmbeddingPort.BATCH)).stream()
                    .map(Chunk::text).toList();
            switch (port.einbetten(texte)) {
                case EmbeddingPort.Vektoren v -> {
                    erfassen(v.verbrauch());
                    vektoren.addAll(v.vektoren());
                }
                case EmbeddingPort.Ausfall a -> {
                    erfassen(a.verbrauch());
                    throw new EmbeddingAusfall(dokumentId, a.grund());
                }
                case EmbeddingPort.OhneVektoren o -> {
                    return Optional.empty();
                }
                case EmbeddingPort.OhneZugang o -> {
                    LOG.warnf("Abschnitte von Dokument %s ohne Embedding: kein Token für das Embedding-Modell",
                            dokumentId);
                    return Optional.empty();
                }
            }
        }
        return Optional.of(List.copyOf(vektoren));
    }

    private void erfassen(KiPort.Verbrauch eintrag) {
        try {
            verbrauch.erfassen(KiVerbrauchPort.EMBEDDING, eintrag);
        } catch (RuntimeException e) {
            LOG.warnf("KI-Verbrauch nicht gespeichert (%s)", e.getClass().getName());
        }
    }
}
