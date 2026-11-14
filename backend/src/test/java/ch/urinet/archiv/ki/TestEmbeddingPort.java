package ch.urinet.archiv.ki;

import ch.urinet.archiv.ki.adapter.out.EmbeddingAuswahl;
import ch.urinet.archiv.ki.adapter.out.langchain4j.LangChain4jEmbeddingAdapter;
import ch.urinet.archiv.ki.adapter.out.stub.EmbeddingStub;
import ch.urinet.archiv.ki.application.EmbeddingPort;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * {@link EmbeddingPort} für die Tests: ohne Eingriff wie {@link EmbeddingAuswahl} (Stub oder LangChain4j-Adapter gegen
 * den WireMock); die Szenarien zum Pfad ohne Vektoren schalten auf die Antwort des Dev-Stubs ({@code OhneVektoren})
 * oder auf «kein Token» ({@code OhneZugang}) und setzen danach zurück ({@code EmbeddingSchritte},
 * {@code HybridSchritte}).
 */
@Mock
@ApplicationScoped
public class TestEmbeddingPort implements EmbeddingPort {

    /** Antwort statt des gewählten Ports; {@code null} heisst wie {@link EmbeddingAuswahl}. */
    public enum Ersatz { STUB, OHNE_ZUGANG }

    @ConfigProperty(name = "archiv.ki.embedding-stub")
    boolean stub;

    @Inject
    EmbeddingStub dev;

    @Inject
    Instance<LangChain4jEmbeddingAdapter> echt;

    private volatile Ersatz ersatz;

    @Override
    public Antwort einbetten(List<String> texte) {
        Ersatz wert = ersatz;
        if (wert == Ersatz.STUB) {
            return dev.einbetten(texte);
        }
        if (wert == Ersatz.OHNE_ZUGANG) {
            return new OhneZugang();
        }
        return stub ? dev.einbetten(texte) : echt.get().einbetten(texte);
    }

    @Override
    public Antwort einbettenFrage(String frage) {
        Ersatz wert = ersatz;
        if (wert == Ersatz.STUB) {
            return dev.einbettenFrage(frage);
        }
        if (wert == Ersatz.OHNE_ZUGANG) {
            return new OhneZugang();
        }
        return stub ? dev.einbettenFrage(frage) : echt.get().einbettenFrage(frage);
    }

    public void setzen(Ersatz wert) {
        ersatz = wert;
    }
}
