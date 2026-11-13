package ch.urinet.archiv.ki.application;

import java.util.UUID;

/**
 * Das Embedding-Modell hat nicht passend geantwortet: Fehlversuch des Auftrags {@code EMBEDDING}, wiederholt nach
 * Spec 01, Req 5.4. Die Abschnitte des Dokuments bleiben dabei unverändert. Die Meldung nennt nur die Dokument-ID und
 * Klassennamen (Req 16.7).
 */
public class EmbeddingAusfall extends RuntimeException {

    public EmbeddingAusfall(UUID dokumentId, String grund) {
        super("Embedding-Modell ohne passende Antwort für Dokument " + dokumentId + " (" + grund + ")");
    }
}
