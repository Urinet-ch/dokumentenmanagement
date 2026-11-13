package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestDaten.ManifestStand;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Was die Erzeugung zu einem Datum festgehalten hat ({@code manifest}, {@code manifest_zeitstempel}). Prüft das
 * Prüfwerkzeug mit Datenbank, bestimmt dies, welche Zeitstempel zum Manifest gehören, auch für den jüngsten Tag ohne
 * Folgemanifest; ohne Datenbank kommt das nur aus dem Folgemanifest ({@link ManifestPruefen}).
 */
@FunctionalInterface
public interface FestgehalteneManifeste {

    /** Leer, wenn zum Datum kein Manifest festgehalten ist. */
    Optional<ManifestStand> stand(MandantCode mandant, LocalDate datum);
}
