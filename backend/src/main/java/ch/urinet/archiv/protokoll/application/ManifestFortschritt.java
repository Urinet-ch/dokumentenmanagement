package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Driven Port: Stand der Manifest-Kette eines Mandanten für die Überwachung (Req 15.8, 16.5), ohne Mandant der Anfrage
 * (läuft im Scheduler).
 */
public interface ManifestFortschritt {

    /** Letztes Datum mit Manifest und Zeitpunkt des zuletzt festgehaltenen Manifests. */
    record Stand(LocalDate letztesDatum, Instant zuletztErstellt) {
    }

    /** Leer, solange der Mandant kein Manifest hat. */
    Optional<Stand> stand(MandantCode mandant);
}
