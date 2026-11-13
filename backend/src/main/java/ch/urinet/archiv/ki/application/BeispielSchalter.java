package ch.urinet.archiv.ki.application;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Schalter {@code archiv.ki.beispiele.aktiv} (Req 7.9; design.md «Umsetzung (Aufgabe 3.3)»): Korrekturbeispiele kommen
 * nur eingeschaltet in den Prompt. Eigene Bean, damit die Tests mit dem Wert aus prod laufen und nur einzelne Szenarien
 * ihn einschalten.
 */
@ApplicationScoped
public class BeispielSchalter {

    private final boolean aktiv;

    public BeispielSchalter(@ConfigProperty(name = "archiv.ki.beispiele.aktiv") boolean aktiv) {
        this.aktiv = aktiv;
    }

    public boolean aktiv() {
        return aktiv;
    }
}
