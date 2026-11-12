package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.Befund;

import java.util.List;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Driven Port: benachrichtigt den Inhaber eines Mandanten (Req 6.4, 16.5). Die Nachricht nennt nur IDs und Status, nie
 * Inhalte, Beträge oder Personendaten (Req 16.7). Ein Fehler beim Versand darf den Aufrufer nicht scheitern lassen.
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
     * der Lauf bleibt an {@code stand} stehen und wird in diesem Monat nicht fortgesetzt. {@code bisher} sind die Zähler
     * der schon festgehaltenen Teile, {@code erste} die ersten der bis dahin festgehaltenen Abweichungen (leer ohne
     * Abweichungen), damit der Inhaber sie auch ohne Abschluss des Laufs erfährt (Req 15.5). Nur IDs: Phase,
     * Dokument-ID und S3-Schlüssel der Marke, Art, Dokument-ID, Schlüssel und Version je Abweichung, Fehler-ID
     * (Req 16.7).
     */
    void prueflaufAbgebrochen(MandantCode mandant, UUID laufId, int teil, PrueflaufRegister.Fortsetzung stand,
                              PrueflaufRegister.Zaehler bisher, List<PrueflaufRegister.Abweichung> erste,
                              UUID fehlerId);

    /** Für {@code datum} liegt nach dem letzten Versuch kein gültiges Manifest vor (Req 15.8, Aufgabe 5.2). */
    void manifestFehlt(MandantCode mandant, LocalDate datum, UUID fehlerId);

    /** Alarm der Überwachung (Req 16.5); {@code true} nur, wenn die Mail zugestellt wurde. */
    boolean alarm(MandantCode mandant, Befund befund);
}
