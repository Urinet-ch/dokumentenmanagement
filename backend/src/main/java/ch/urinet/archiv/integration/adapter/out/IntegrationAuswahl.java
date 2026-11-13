package ch.urinet.archiv.integration.adapter.out;

import ch.urinet.archiv.integration.adapter.out.imap.ImapPostfach;
import ch.urinet.archiv.integration.adapter.out.stub.IntegrationStub;
import ch.urinet.archiv.integration.adapter.out.stub.PostfachStub;
import ch.urinet.archiv.integration.application.IntegrationPort;
import ch.urinet.archiv.integration.application.mail.PostfachPort;
import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

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

    /** Mail-Eingang (Spec 02, Aufgabe 6.1): IMAP oder, im Profil dev, ein Postfach ohne Nachrichten. */
    @Produces
    @ApplicationScoped
    public PostfachPort postfach(@ConfigProperty(name = "archiv.integration.stub") boolean stub, PostfachStub dev,
                                 ImapPostfach imap) {
        return stub ? dev : imap;
    }

    @Produces
    @Dependent
    public PortAuswahl postfachAuswahl(@ConfigProperty(name = "archiv.integration.stub") boolean stub) {
        return new PortAuswahl("PostfachPort", stub, true, Optional.empty());
    }

    /** Für die Startprüfung der Port-Auswahl: Der Adapter folgt mit Spec 02/03. */
    @Produces
    @Dependent
    public PortAuswahl auswahl(@ConfigProperty(name = "archiv.integration.stub") boolean stub) {
        return new PortAuswahl("IntegrationPort", stub, false, Optional.of("Spec 02/03"));
    }
}
