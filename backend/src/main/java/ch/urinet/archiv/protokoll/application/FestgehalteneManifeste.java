package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestDaten.ManifestStand;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Was die Erzeugung zu einem Datum festgehalten hat ({@code manifest}, {@code manifest_zeitstempel}): eindeutige
 * Quelle des Prüfwerkzeugs dafür, welche Zeitstempel zum Manifest gehören, auch für den jüngsten Tag ohne Folgemanifest.
 */
@FunctionalInterface
public interface FestgehalteneManifeste {

    /** Leer, wenn zum Datum kein Manifest festgehalten ist. */
    Optional<ManifestStand> stand(MandantCode mandant, LocalDate datum);
}
