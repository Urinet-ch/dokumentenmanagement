package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.Befund;

import java.util.List;
import java.util.Optional;

/**
 * Driven Port: fehlende oder ungültige Tages-Manifeste eines Mandanten (Req 15.8, 16.5), Befunde der Quelle
 * {@link Befund.Quelle#MANIFEST} mit dem Datum als Gegenstand. Die Umsetzung bringt das Manifest (Aufgabe 5.2).
 */
public interface ManifestUeberwachung {

    /** Leer heisst «nicht angebunden»: nichts geprüft, bestehende Alarme der Quelle bleiben unverändert. */
    Optional<List<Befund>> befunde(MandantCode mandant);
}
