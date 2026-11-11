package ch.urinet.archiv.gemeinsam.adapter.in.health;

import io.quarkus.runtime.Quarkus;
import jakarta.enterprise.context.ApplicationScoped;

/** Beendet die Anwendung geordnet mit einem Exit-Code (eigene Bean, damit Tests den Prozess nicht beenden). */
@ApplicationScoped
public class ProzessEnde {

    public void beenden(int code) {
        Quarkus.asyncExit(code);
    }
}
