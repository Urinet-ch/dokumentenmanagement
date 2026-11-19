package ch.urinet.archiv.dokument.application;

import java.util.Set;
import java.util.UUID;

/** Ereignisse, die andere Fachbereiche synchron mit einer Dokumentänderung verarbeiten. */
public interface DokumentEreignisse {

    /** Dokument erreicht {@code ARCHIVIERT}; Aufträge müssen Teil derselben Transaktion sein. */
    void archiviert(UUID dokumentId, String art);

    /** Vor dem Sperren einer Dokumentzeile aufrufen, damit alle Beteiligten dieselbe Sperrreihenfolge einhalten. */
    void vorAenderung();

    /** Dokumentänderung innerhalb derselben Transaktion melden; {@code felder} nennt die betroffenen Bereiche. */
    void geaendert(UUID dokumentId, Set<String> felder);
}
