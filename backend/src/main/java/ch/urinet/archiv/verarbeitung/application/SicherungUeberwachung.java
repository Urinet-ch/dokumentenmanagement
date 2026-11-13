package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.domain.Befund;

import java.util.List;
import java.util.Optional;

/**
 * Driven Port: Stand des Sicherungs-Exports auf dem Server (Req 16.5), Befunde der Quelle
 * {@link Befund.Quelle#SICHERUNG}, z. B. fehlgeschlagen oder zu alt. Die Umsetzung bringt der Export (Aufgabe 5.5); den
 * Fehlschlag des Sicherungs-Workflows meldet zusätzlich der Workflow selbst.
 */
public interface SicherungUeberwachung {

    /** Leer heisst «nicht angebunden»: nichts geprüft, bestehende Alarme der Quelle bleiben unverändert. */
    Optional<List<Befund>> befunde();
}
