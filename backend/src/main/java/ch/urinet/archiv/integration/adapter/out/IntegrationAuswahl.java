package ch.urinet.archiv.integration.adapter.out;

import ch.urinet.archiv.integration.adapter.out.stub.IntegrationStub;
import ch.urinet.archiv.integration.application.IntegrationPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Wählt zur Laufzeit zwischen Dev-Stub und echtem Adapter (Schalter per Konfiguration, nicht per Build-Profil). */
@ApplicationScoped
public class IntegrationAuswahl {

    @Produces
    @ApplicationScoped
    public IntegrationPort integration(@ConfigProperty(name = "archiv.integration.stub") boolean stub, IntegrationStub dev) {
        if (stub) {
            return dev;
        }
        throw new IllegalStateException("Kein Adapter für IntegrationPort vor Spec 02/03; archiv.integration.stub=true setzen");
    }
}
