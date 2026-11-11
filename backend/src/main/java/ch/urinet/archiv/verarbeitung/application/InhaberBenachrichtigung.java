package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.UUID;

/**
 * Driven Port: benachrichtigt den Inhaber eines Mandanten (Req 6.4). Die Nachricht nennt nur IDs, nie Inhalte, Beträge
 * oder Personendaten (Req 16.7). Ein Fehler beim Versand darf den Aufrufer nicht scheitern lassen.
 */
public interface InhaberBenachrichtigung {

    /** Die Archivierung von {@code dokumentId} ist gescheitert; das Dokument steht in {@code ARCHIVIERUNG_FEHLER}. */
    void archivierungGescheitert(MandantCode mandant, UUID dokumentId, UUID fehlerId);
}
