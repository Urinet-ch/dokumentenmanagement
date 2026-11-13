package ch.urinet.archiv.ki.application;

/**
 * Driven Port für {@code ki_verbrauch} im Schema des Mandanten aus dem Kontext (design.md «KI»): eine Zeile je Aufruf
 * des Sprachmodells bzw. des Embedding-Modells, in einer eigenen Transaktion, damit der Verbrauch auch bei einem
 * späteren Fehler bleibt.
 */
public interface KiVerbrauchPort {

    String ERFASSUNG = "ERFASSUNG";
    /** Embeddings der Abschnitte archivierter Dokumente (Spec 02, Req 2.1, 2.4). */
    String EMBEDDING = "EMBEDDING";

    void erfassen(String zweck, KiPort.Verbrauch verbrauch);
}
