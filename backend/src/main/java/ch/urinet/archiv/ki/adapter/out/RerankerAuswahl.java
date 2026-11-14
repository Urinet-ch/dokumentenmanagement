package ch.urinet.archiv.ki.adapter.out;

import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import ch.urinet.archiv.ki.adapter.out.reranker.InfomaniakReranker;
import ch.urinet.archiv.ki.adapter.out.stub.RerankerStub;
import ch.urinet.archiv.ki.application.RerankerPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/** Wählt zur Laufzeit zwischen Dev-Stub und REST-Adapter des Rerankers ({@code archiv.ki.reranker-stub}). */
@ApplicationScoped
public class RerankerAuswahl {

    @Produces
    @ApplicationScoped
    public RerankerPort reranker(@ConfigProperty(name = "archiv.ki.reranker-stub") boolean stub, RerankerStub dev,
                                 Instance<InfomaniakReranker> echt) {
        return stub ? dev : echt.get();
    }

    /** Für die Startprüfung der Port-Auswahl (Spec 01, Req 3.2). */
    @Produces
    @Dependent
    public PortAuswahl auswahl(@ConfigProperty(name = "archiv.ki.reranker-stub") boolean stub) {
        return new PortAuswahl("RerankerPort", stub, true, Optional.empty());
    }
}
