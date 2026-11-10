package ch.urinet.archiv.dokumentart.application;

/**
 * Zahl der Dokumente einer Art im Mandanten der laufenden Anfrage, verworfene eingeschlossen (Req 9.4). Umsetzung:
 * {@code DokumentZaehler} im Fachbereich {@code dokument}.
 */
public interface DokumentZaehlerPort {

    long anzahl(String art);
}
