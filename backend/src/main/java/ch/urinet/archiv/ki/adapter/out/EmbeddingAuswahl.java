package ch.urinet.archiv.ki.adapter.out;

import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import ch.urinet.archiv.ki.adapter.out.langchain4j.LangChain4jEmbeddingAdapter;
import ch.urinet.archiv.ki.adapter.out.stub.EmbeddingStub;
import ch.urinet.archiv.ki.application.EmbeddingPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/** Wählt zur Laufzeit zwischen Dev-Stub und LangChain4j-Adapter der Embeddings ({@code archiv.ki.embedding-stub}). */
@ApplicationScoped
public class EmbeddingAuswahl {

    @Produces
    @ApplicationScoped
    public EmbeddingPort embedding(@ConfigProperty(name = "archiv.ki.embedding-stub") boolean stub, EmbeddingStub dev,
                                   Instance<LangChain4jEmbeddingAdapter> echt) {
        return stub ? dev : echt.get();
    }

    /** Für die Startprüfung der Port-Auswahl (Spec 01, Req 3.2). */
    @Produces
    @Dependent
    public PortAuswahl auswahl(@ConfigProperty(name = "archiv.ki.embedding-stub") boolean stub) {
        return new PortAuswahl("EmbeddingPort", stub, true, Optional.empty());
    }
}
