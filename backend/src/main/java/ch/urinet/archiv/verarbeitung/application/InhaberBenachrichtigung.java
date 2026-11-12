package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.List;
import java.util.UUID;

/**
 * Driven Port: benachrichtigt den Inhaber eines Mandanten (Req 6.4). Die Nachricht nennt nur IDs, nie Inhalte, Beträge
 * oder Personendaten (Req 16.7). Ein Fehler beim Versand darf den Aufrufer nicht scheitern lassen.
 */
public interface InhaberBenachrichtigung {

    /** Die Archivierung von {@code dokumentId} ist gescheitert; das Dokument steht in {@code ARCHIVIERUNG_FEHLER}. */
    void archivierungGescheitert(MandantCode mandant, UUID dokumentId, UUID fehlerId);

    /**
     * Der Prüflauf {@code laufId} hat Abweichungen festgestellt (Req 15.5, 15.10); {@code erste} sind die ersten davon,
     * nur mit Art, Dokument-ID, S3-Schlüssel und Versions-ID.
     */
    void prueflaufAbweichungen(MandantCode mandant, UUID laufId, PrueflaufRegister.Zaehler zaehler,
                               List<PrueflaufRegister.Abweichung> erste);
}
