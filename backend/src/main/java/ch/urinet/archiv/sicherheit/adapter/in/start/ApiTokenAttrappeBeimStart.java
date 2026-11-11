package ch.urinet.archiv.sicherheit.adapter.in.start;

import ch.urinet.archiv.sicherheit.application.ApiTokenPruefung;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Berechnet den Attrappen-Hash für unbekannte Token-IDs beim Start (Req 2.5), damit die erste Anfrage mit unbekannter
 * ID nicht zusätzlich ein {@code hashen} rechnet. Der Hash läuft über die {@code HashSperre}; so entsteht sie beim
 * Start, und ungültige Einstellungen ({@code archiv.api-token.parallele-pruefungen} &lt; 1, negative
 * {@code archiv.api-token.wartezeit}) brechen den Start ab statt jede Token-Anfrage mit 500.
 */
@ApplicationScoped
public class ApiTokenAttrappeBeimStart {

    void beimStart(@Observes StartupEvent start, ApiTokenPruefung pruefung) {
        pruefung.attrappeBerechnen();
    }
}
