package ch.urinet.archiv.ablage.adapter.out;

import ch.urinet.archiv.ablage.adapter.out.stub.AblageStub;
import ch.urinet.archiv.ablage.application.AblagePort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Wählt zur Laufzeit zwischen Dev-Stub und echtem Adapter (Schalter per Konfiguration, nicht per Build-Profil). */
@ApplicationScoped
public class AblageAuswahl {

    @Produces
    @ApplicationScoped
    public AblagePort ablage(@ConfigProperty(name = "archiv.ablage.stub") boolean stub, AblageStub dev) {
        if (stub) {
            return dev;
        }
        throw new IllegalStateException("Kein Adapter für AblagePort vor Aufgabe 2.3; archiv.ablage.stub=true setzen");
    }
}
