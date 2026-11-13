package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Handler für {@code EMBEDDING_ALLE} (Spec 02, Aufgabe 6.3; Req 2.4): Nachberechnung der Embeddings bestehender
 * Dokumente in Teilen zu {@link EmbeddingSpeichern#TEIL} archivierten Dokumenten (nach ID): je Dokument ein Auftrag
 * {@code EMBEDDING}, bei einem vollen Teil der nächste Teil. Ruft selbst kein Modell auf, darum bleibt ein Teil kurz und
 * die Embeddings laufen einzeln mit Wiederholung. Mit {@code nurFehlende} nur Dokumente ohne Abschnitte oder mit einem
 * Abschnitt ohne Vektor.
 */
@ApplicationScoped
public class EmbeddingAlleHandler implements AuftragHandler {

    private static final Logger LOG = Logger.getLogger(EmbeddingAlleHandler.class);

    private final EmbeddingSpeichern speichern;

    public EmbeddingAlleHandler(EmbeddingSpeichern speichern) {
        this.speichern = speichern;
    }

    @Override
    public AuftragTyp typ() {
        return EmbeddingSpeichern.EMBEDDING_ALLE;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) {
        Optional<UUID> nach = auftrag.nutzlast().uuid(EmbeddingSpeichern.NACH);
        boolean nurFehlende = auftrag.nutzlast().schalter(EmbeddingSpeichern.NUR_FEHLENDE).orElse(false);
        int anzahl = speichern.teilEinreihen(auftrag, nach, nurFehlende);
        LOG.infof("Nachberechnung der Embeddings (Auftrag %d): %d Dokumente eingereiht", auftrag.id(), anzahl);
    }
}
