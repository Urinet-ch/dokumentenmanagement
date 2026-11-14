package ch.urinet.archiv.ki;

import ch.urinet.archiv.ki.adapter.out.RerankerAuswahl;
import ch.urinet.archiv.ki.adapter.out.reranker.InfomaniakReranker;
import ch.urinet.archiv.ki.adapter.out.stub.RerankerStub;
import ch.urinet.archiv.ki.application.RerankerPort;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * {@link RerankerPort} für die Tests: ohne Eingriff wie {@link RerankerAuswahl} (Stub oder REST-Adapter gegen den
 * WireMock); die Szenarien der Hybridsuche schalten auf die Antwort des Dev-Stubs oder auf «kein Token» und setzen
 * danach zurück ({@code HybridSchritte}).
 */
@Mock
@ApplicationScoped
public class TestRerankerPort implements RerankerPort {

    /** Antwort statt des gewählten Ports; {@code null} heisst wie {@link RerankerAuswahl}. */
    public enum Ersatz { STUB, OHNE_ZUGANG }

    @ConfigProperty(name = "archiv.ki.reranker-stub")
    boolean stub;

    @Inject
    RerankerStub dev;

    @Inject
    Instance<InfomaniakReranker> echt;

    private volatile Ersatz ersatz;

    @Override
    public Antwort sortieren(String frage, List<String> texte, int topN) {
        Ersatz wert = ersatz;
        if (wert == Ersatz.STUB) {
            return dev.sortieren(frage, texte, topN);
        }
        if (wert == Ersatz.OHNE_ZUGANG) {
            return new OhneZugang();
        }
        return stub ? dev.sortieren(frage, texte, topN) : echt.get().sortieren(frage, texte, topN);
    }

    public void setzen(Ersatz wert) {
        ersatz = wert;
    }
}
