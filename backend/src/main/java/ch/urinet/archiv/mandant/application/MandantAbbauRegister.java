package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Driven Port für die persistente Abbau-Freigabe im öffentlichen Mandantenregister. */
public interface MandantAbbauRegister {

    record Status(Mandant mandant, Optional<Instant> deaktiviertAm, Optional<UUID> vollexportBestaetigt,
                  boolean abgeschlossen) {
    }

    Optional<Status> status(MandantCode code);

    /** Deaktiviert den Mandanten und zeichnet die bestätigte Export-ID sowie wer und warum einmalig auf. */
    boolean deaktivieren(MandantCode code, UUID vollexportId, Instant jetzt, String wer, String grund);

    /** Markiert den Abbau erst nach Entfernen des Schemas und des leeren Buckets als abgeschlossen. */
    boolean abschliessen(MandantCode code);
}
