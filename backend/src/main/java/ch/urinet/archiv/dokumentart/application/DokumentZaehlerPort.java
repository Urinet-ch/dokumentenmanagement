package ch.urinet.archiv.dokumentart.application;

/**
 * Zahl der Dokumente einer Art im Mandanten der laufenden Anfrage, verworfene eingeschlossen (Req 9.4). Die Tabelle
 * {@code dokument} entsteht mit Aufgabe 2.2; bis dahin zählt {@code OhneDokumenteZaehler} null Dokumente.
 */
public interface DokumentZaehlerPort {

    long anzahl(String art);
}
