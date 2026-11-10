package ch.urinet.archiv.sicherheit.adapter.in.start;

import ch.urinet.archiv.sicherheit.application.ApiTokenPruefung;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Berechnet den Attrappen-Hash für unbekannte Token-IDs beim Start (Req 2.5), damit die erste Anfrage mit unbekannter
 * ID nicht zusätzlich ein {@code hashen} ausserhalb der Sperre der Prüfungen rechnet.
 */
@ApplicationScoped
public class ApiTokenAttrappeBeimStart {

    void beimStart(@Observes StartupEvent start, ApiTokenPruefung pruefung) {
        pruefung.attrappeBerechnen();
    }
}
