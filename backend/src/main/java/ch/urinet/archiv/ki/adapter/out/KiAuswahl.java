package ch.urinet.archiv.ki.adapter.out;

import ch.urinet.archiv.ki.adapter.out.stub.KiStub;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/** Wählt zur Laufzeit zwischen Dev-Stub und echtem Adapter (Schalter per Konfiguration, nicht per Build-Profil). */
@ApplicationScoped
public class KiAuswahl {

    @Produces
    @ApplicationScoped
    public KiPort ki(@ConfigProperty(name = "archiv.ki.stub") boolean stub, KiStub dev) {
        if (stub) {
            return dev;
        }
        throw new IllegalStateException("Kein Adapter für KiPort vor Aufgabe 3.2; archiv.ki.stub=true setzen");
    }

    /** Für die Startprüfung der Port-Auswahl: Der Adapter folgt mit 3.2. */
    @Produces
    @Dependent
    public PortAuswahl auswahl(@ConfigProperty(name = "archiv.ki.stub") boolean stub) {
        return new PortAuswahl("KiPort", stub, false, Optional.of("3.2"));
    }
}
