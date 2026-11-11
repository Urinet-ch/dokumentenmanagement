package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;

import java.util.UUID;

/**
 * Führt Aufträge eines Typs aus (ab Aufgabe 2.6). Der Worker ruft {@link #ausfuehren} im Kontext des Mandanten aus dem
 * Auftrag auf, in einem eigenen Thread ohne Transaktion; die Transaktion gehört in den Handler. Ein Handler muss
 * idempotent sein: Nach einer Zeitüberschreitung, einem verwaisten Lauf oder einem nicht gespeicherten Abschluss kommt
 * derselbe Auftrag erneut. Eine Unterbrechung ({@link Thread#interrupted()}) heisst Abbruch wegen Zeitgrenze,
 * verlorenem Anspruch oder Herunterfahren; ein Handler soll darauf enden, sonst bleibt sein Platz belegt. Nach einer
 * Unterbrechung kehrt er nie normal zurück, sondern wirft {@link InterruptedException} (oder eine andere Ausnahme); der
 * Worker wertet einen beim Herunterfahren unterbrochenen Lauf ohnehin nie als Abschluss.
 * <p>
 * Pflicht für jeden Handler (design.md «Verarbeitung»): Jede Ein- und Ausgabe hat eine eigene Zeitgrenze unter der
 * Zeitgrenze des Typs (JDBC {@code queryTimeout} bzw. {@code statement_timeout}, S3 {@code apiCallTimeout}, externe
 * Prozesse mit {@code destroyForcibly}), weil eine Unterbrechung blockierende Ein- und Ausgabe nicht sicher beendet.
 * Ergebnisse schreibt er nur in einer Transaktion, die zuerst {@link AuftragService#anspruchSichern} aufruft: Hält der
 * Anspruch nicht mehr (freigegeben, ein anderer Lauf hat den Auftrag), rollt sie zurück. Über JVMs hinweg kann ein
 * hängender Handler sonst neben dem nächsten Versuch weiterschreiben.
 */
public interface AuftragHandler {

    AuftragTyp typ();

    void ausfuehren(Auftrag auftrag) throws Exception;

    /**
     * Nach dem letzten Fehlversuch, wenn der Auftrag schon {@code FEHLER} ist, im Kontext des Mandanten; z. B. setzt
     * {@code VERARBEITEN} hier das Dokument auf {@code FEHLER} (Req 5.4). {@code fehlerId} steht auch in
     * {@code auftrag.fehler} und im Log; eine Meldung gibt es bewusst nicht (Req 16.7). Muss idempotent sein: Endet der
     * Hook nicht erfolgreich (Absturz, Ausnahme, Zeitgrenze), ruft der Aufräumer ihn mit Wartezeit erneut auf,
     * höchstens {@link ch.urinet.archiv.verarbeitung.domain.Nachbearbeitung#MAX_VERSUCHE}-mal. Er belegt einen Platz wie
     * ein Handler und gehorcht denselben Pflichten. Standard: nichts.
     */
    default void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
    }
}
