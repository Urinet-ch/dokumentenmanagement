package ch.urinet.archiv.ki.application;

/**
 * Driven Port für {@code ki_verbrauch} im Schema des Mandanten aus dem Kontext (design.md «KI»): eine Zeile je Aufruf
 * des Sprachmodells, des Embedding-Modells bzw. des Rerankers, in einer eigenen Transaktion, damit der Verbrauch auch bei einem
 * späteren Fehler bleibt.
 */
public interface KiVerbrauchPort {

    String ERFASSUNG = "ERFASSUNG";
    /** Embeddings der Abschnitte archivierter Dokumente (Spec 02, Req 2.1, 2.4). */
    String EMBEDDING = "EMBEDDING";
    /**
     * Hybridsuche (Spec 02, Req 2.8; Aufgabe 6.4, entschieden von Fabian am 14.11.2026): eine Zeile je Aufruf von
     * Embedding-Modell (Frage einbetten) und Reranker mit Antwort, ohne Limit.
     */
    String SUCHE = "SUCHE";

    void erfassen(String zweck, KiPort.Verbrauch verbrauch);
}
