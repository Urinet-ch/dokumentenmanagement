package ch.urinet.archiv.ki;

import ch.urinet.archiv.ki.application.BeispielSchalter;
import io.quarkus.test.Mock;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@link BeispielSchalter} für die Tests: ohne Eingriff der Wert aus der Konfiguration (wie in prod aus); nur Szenarien
 * zu Req 7.9 schalten die Beispiele ein und setzen danach zurück ({@code KiLernenSchritte}). {@code @Singleton} ohne
 * Client-Proxy, weil die Oberklasse keinen Konstruktor ohne Argumente hat.
 */
@Mock
@Singleton
public class TestBeispielSchalter extends BeispielSchalter {

    private volatile Boolean gesetzt;

    public TestBeispielSchalter(@ConfigProperty(name = "archiv.ki.beispiele.aktiv") boolean aktiv) {
        super(aktiv);
    }

    @Override
    public boolean aktiv() {
        Boolean wert = gesetzt;
        return wert != null ? wert : super.aktiv();
    }

    /** {@code null} heisst Wert aus der Konfiguration. */
    public void setzen(Boolean wert) {
        gesetzt = wert;
    }
}
