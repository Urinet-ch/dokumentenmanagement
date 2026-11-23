package ch.urinet.archiv.ki.application;

import java.util.UUID;

/**
 * Driven Port für {@code ki_verbrauch} im Schema des Mandanten aus dem Kontext (design.md «KI»): eine Zeile je Aufruf
 * des Sprachmodells, des Embedding-Modells bzw. des Rerankers, in einer eigenen Transaktion, damit der Verbrauch auch bei einem
 * späteren Fehler bleibt.
 */
public interface KiVerbrauchPort {

    String ERFASSUNG = "ERFASSUNG";
    /** Embeddings der Abschnitte archivierter Dokumente (Spec 02, Req 2.1, 2.4). */
    String EMBEDDING = "EMBEDDING";
    /** Sprachmodell-Aufrufe beim Ingest einer Wissensquelle (Spec 04, Req 13.6). */
    String WISSEN_INGEST = "WISSEN_INGEST";
    /** Sprachmodell-Aufrufe beim Lint der Wissensbasis (Spec 04, Req 13.6). */
    String WISSEN_LINT = "WISSEN_LINT";
    /** Embedding-Aufrufe für Wissensseiten (Spec 04, Req 13.6). */
    String WISSEN_EMBEDDING = "WISSEN_EMBEDDING";
    /**
     * Hybridsuche (Spec 02, Req 2.8; Aufgabe 6.4, entschieden von Fabian am 14.11.2026): eine Zeile je Aufruf von
     * Embedding-Modell (Frage einbetten) und Reranker mit Antwort, ohne Limit.
     */
    String SUCHE = "SUCHE";
    /**
     * Chat-Agent (Spec 02, Req 5.5; Aufgabe 7.2, entschieden von Fabian am 14.11.2026): eine Zeile je Aufruf des
     * Sprachmodells, also je Werkzeug-Runde und für die Schlussantwort, mit Modell und Tokens, ohne Frage und Inhalte;
     * nur protokollieren wie {@link #SUCHE}, kein Budget und keine Sperre.
     */
    String CHAT = "CHAT";

    /**
     * Sprachmodell-Aufrufe des Wissenschats (Spec 04, Requirement 21.7): eine Zeile je Aufruf wie {@link #CHAT}, zählt
     * aber zum Monatsbudget der Wissensbasis (Requirement 13.6, Zweck {@code WISSEN_*}).
     */
    String WISSEN_CHAT = "WISSEN_CHAT";

    void erfassen(String zweck, KiPort.Verbrauch verbrauch);

    default void erfassen(String zweck, KiPort.Verbrauch verbrauch, UUID ingestEntwurfId) {
        if (ingestEntwurfId != null) {
            throw new UnsupportedOperationException("Ingest-Verbrauch muss dem Entwurf zugeordnet werden");
        }
        erfassen(zweck, verbrauch);
    }
}
