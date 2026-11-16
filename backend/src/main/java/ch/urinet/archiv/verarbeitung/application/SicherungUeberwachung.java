package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.domain.Befund;

import java.util.List;
import java.util.Optional;

/**
 * Driven Port: prüft die in {@code public.sicherung_export} protokollierten Stände gegen die Sicherungsfrist
 * (Req 16.5). Den Fehlschlag des Sicherungs-Workflows meldet zusätzlich der Workflow selbst.
 */
public interface SicherungUeberwachung {

    /** Leer heisst «deaktiviert»: nichts geprüft, bestehende Alarme der Quelle bleiben unverändert. */
    Optional<List<Befund>> befunde();
}
