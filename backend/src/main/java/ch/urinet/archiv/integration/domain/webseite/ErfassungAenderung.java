package ch.urinet.archiv.integration.domain.webseite;

import java.time.Instant;
import java.util.UUID;

/**
 * Eintrag des Erfassungs-Feeds (Spec 03, Req 14.2): Status- und Metadatenänderung eines Dokuments des Mandanten.
 * {@code referenz} nur für Dokumente der Webseite des Schlüssels, sonst {@code null}; {@code status} und
 * {@code version} fehlen bei einem physischen Löschen; {@code fehlercode} ist ein stabiler Code ohne Details, nur bei
 * einem Fehlerstatus. Nie Titel, Dateiname oder Inhalt.
 */
public record ErfassungAenderung(UUID dokumentId, String referenz, String status, String fehlercode, Integer version,
                                 Instant zeit) {
}
