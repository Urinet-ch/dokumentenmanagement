package ch.urinet.archiv.ki.adapter.out;

import ch.urinet.archiv.ki.adapter.out.langchain4j.LangChain4jKiAdapter;
import ch.urinet.archiv.ki.adapter.out.stub.KiStub;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/** Wählt zur Laufzeit zwischen Dev-Stub und LangChain4j-Adapter (Schalter per Konfiguration, nicht per Build-Profil). */
@ApplicationScoped
public class KiAuswahl {

    @Produces
    @ApplicationScoped
    public KiPort ki(@ConfigProperty(name = "archiv.ki.stub") boolean stub, KiStub dev,
                     Instance<LangChain4jKiAdapter> echt) {
        return stub ? dev : echt.get();
    }

    /** Für die Startprüfung der Port-Auswahl: LangChain4j gegen Infomaniak AI Services ist der echte Adapter (3.2). */
    @Produces
    @Dependent
    public PortAuswahl auswahl(@ConfigProperty(name = "archiv.ki.stub") boolean stub) {
        return new PortAuswahl("KiPort", stub, true, Optional.empty());
    }
}
