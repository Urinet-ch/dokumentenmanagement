package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;

import java.util.UUID;

/**
 * Führt Aufträge eines Typs aus (ab Aufgabe 2.6). Der Worker ruft {@link #ausfuehren} im Kontext des Mandanten aus dem
 * Auftrag auf, in einem eigenen Thread ohne Transaktion; die Transaktion gehört in den Handler. Ein Handler muss
 * idempotent sein: Nach einer Zeitüberschreitung, einem verwaisten Lauf oder einem nicht gespeicherten Abschluss kommt
 * derselbe Auftrag erneut. Eine Unterbrechung ({@link Thread#interrupted()}) heisst Abbruch wegen Zeitgrenze,
 * verlorenem Anspruch oder Herunterfahren; ein Handler soll darauf enden, sonst bleibt sein Platz belegt.
 */
public interface AuftragHandler {

    AuftragTyp typ();

    void ausfuehren(Auftrag auftrag) throws Exception;

    /**
     * Nach dem letzten Fehlversuch, wenn der Auftrag schon {@code FEHLER} ist, im Kontext des Mandanten; z. B. setzt
     * {@code VERARBEITEN} hier das Dokument auf {@code FEHLER} (Req 5.4). {@code fehlerId} steht auch in
     * {@code auftrag.fehler} und im Log; eine Meldung gibt es bewusst nicht (Req 16.7). Muss idempotent sein: Endet der
     * Hook nicht erfolgreich (Absturz, Ausnahme, Zeitgrenze), ruft der Aufräumer ihn erneut auf. Standard: nichts.
     */
    default void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
    }
}
