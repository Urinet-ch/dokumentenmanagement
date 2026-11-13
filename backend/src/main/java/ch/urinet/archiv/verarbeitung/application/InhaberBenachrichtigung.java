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

    /**
     * Teil {@code teil} des Prüflaufs {@code laufId} ist nach dem letzten Versuch in {@code FEHLER} geendet (Req 15.5);
     * der Lauf bleibt an {@code stand} stehen und wird in diesem Monat nicht fortgesetzt. Nur IDs: Phase, Dokument-ID
     * und S3-Schlüssel der Marke, Fehler-ID (Req 16.7).
     */
    void prueflaufAbgebrochen(MandantCode mandant, UUID laufId, int teil, PrueflaufRegister.Fortsetzung stand,
                              UUID fehlerId);
}
