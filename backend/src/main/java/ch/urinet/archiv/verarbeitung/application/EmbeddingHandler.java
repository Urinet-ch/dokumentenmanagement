package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ki.application.ChunkPort;
import ch.urinet.archiv.ki.application.EmbeddingAusfall;
import ch.urinet.archiv.ki.application.EmbeddingService;
import ch.urinet.archiv.ki.domain.Chunk;
import ch.urinet.archiv.ki.domain.Chunker;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handler für {@code EMBEDDING} (Spec 02, Aufgabe 6.3; Req 2.1, 2.4; design.md «Embeddings und Hybridsuche»): Abschnitte
 * eines archivierten Dokuments mit dem {@link Chunker} bilden, über den {@link EmbeddingService} einbetten und die
 * Abschnitte des Dokuments ersetzen. Eingereiht nach erfolgreichem {@code ARCHIVIEREN} und von {@code EMBEDDING_ALLE}.
 * Antwortet das Modell nicht passend ({@link EmbeddingAusfall}), ist es ein Fehlversuch (Spec 01, Req 5.4); die
 * bisherigen Abschnitte bleiben, das Dokument bleibt {@code ARCHIVIERT}, auch nach dem letzten Versuch. Ein Dokument,
 * das nicht {@code ARCHIVIERT} ist, bleibt ohne Abschnitte. Ohne Embedding-Modell (Stub, kein Zugang) entstehen
 * Abschnitte ohne Vektor, vorhandene Abschnitte mit Vektor bleiben ({@link ChunkPort#ersetzen}). Die Transaktion, die
 * die Abschnitte ersetzt, hat die Zeitgrenze von {@code EMBEDDING} statt der Standardgrenze. Das Log nennt nur IDs und
 * die Dauer des Schreibens.
 */
@ApplicationScoped
public class EmbeddingHandler implements AuftragHandler {

    private static final Logger LOG = Logger.getLogger(EmbeddingHandler.class);

    private final EmbeddingSpeichern speichern;
    private final EmbeddingService embeddings;
    private final TransactionManager transaktionen;
    private final int zeitgrenzeSekunden;

    public EmbeddingHandler(EmbeddingSpeichern speichern, EmbeddingService embeddings, TransactionManager transaktionen,
                            @ConfigProperty(name = "archiv.auftrag.zeitgrenze.EMBEDDING") Duration zeitgrenze) {
        this.speichern = speichern;
        this.embeddings = embeddings;
        this.transaktionen = transaktionen;
        this.zeitgrenzeSekunden = Math.toIntExact(Math.max(1, zeitgrenze.toSeconds()));
    }

    @Override
    public AuftragTyp typ() {
        return EmbeddingSpeichern.EMBEDDING;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) throws Exception {
        UUID id = auftrag.nutzlast().uuid(VerarbeitenHandler.DOKUMENT_ID)
                .orElseThrow(() -> new IllegalArgumentException("Nutzlast ohne " + VerarbeitenHandler.DOKUMENT_ID));
        ChunkPort.Stand stand = speichern.laden(id)
                .orElseThrow(() -> new IllegalStateException("Dokument fehlt im Mandanten: " + id));
        if (!stand.archiviert()) {
            LOG.infof("Embeddings übersprungen: Dokument %s ist nicht archiviert", id);
            return;
        }
        List<Chunk> chunks = Chunker.zerlegen(stand.quelle());
        Optional<List<float[]>> vektoren = embeddings.einbetten(id, chunks);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Embeddings unterbrochen");
        }
        List<ChunkPort.Eingebettet> eingebettet = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            float[] vektor = vektoren.isPresent() ? vektoren.get().get(i) : null;
            eingebettet.add(new ChunkPort.Eingebettet(chunks.get(i), vektor));
        }
        long beginn = System.nanoTime();
        boolean ersetzt;
        // Standard der Transaktion (60 s) reicht auf Stufe A für grosse Dokumente nicht; die Zeitgrenze des Auftrags
        // begrenzt den Lauf ohnehin, jede Anweisung hat ihre eigene
        transaktionen.setTransactionTimeout(zeitgrenzeSekunden);
        try {
            ersetzt = speichern.ersetzen(auftrag, id, eingebettet);
        } finally {
            zuruecksetzen();
        }
        if (ersetzt) {
            LOG.infof("Dokument %s: %d Abschnitte %s in %d ms geschrieben", id, chunks.size(),
                    vektoren.isPresent() ? "mit Embedding" : "ohne Embedding",
                    (System.nanoTime() - beginn) / 1_000_000);
        } else {
            LOG.infof("Dokument %s: ohne Embedding-Modell bleiben die vorhandenen Abschnitte", id);
        }
    }

    private void zuruecksetzen() {
        try {
            transaktionen.setTransactionTimeout(0);
        } catch (SystemException e) {
            LOG.warnf("Zeitgrenze der Transaktion nicht zurückgesetzt: %s", e.getClass().getSimpleName());
        }
    }
}
