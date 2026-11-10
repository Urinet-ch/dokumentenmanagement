package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;

/**
 * Führt Aufträge eines Typs aus (ab Aufgabe 2.6). Der Worker ruft {@link #ausfuehren} im Kontext des Mandanten aus dem
 * Auftrag auf, in einem eigenen Thread ohne Transaktion; die Transaktion gehört in den Handler. Ein Handler muss
 * idempotent sein: Nach einer Zeitüberschreitung oder einem verwaisten Lauf kommt derselbe Auftrag erneut.
 * Eine Unterbrechung ({@link Thread#interrupted()}) heisst Abbruch wegen Zeitgrenze oder verlorenem Anspruch.
 */
public interface AuftragHandler {

    AuftragTyp typ();

    void ausfuehren(Auftrag auftrag) throws Exception;

    /**
     * Nach dem letzten Fehlversuch, wenn der Auftrag schon {@code FEHLER} ist, im Kontext des Mandanten; z. B. setzt
     * {@code VERARBEITEN} hier das Dokument auf {@code FEHLER} (Req 5.4). Standard: nichts.
     */
    default void nachLetztemFehlversuch(Auftrag auftrag) {
    }
}
