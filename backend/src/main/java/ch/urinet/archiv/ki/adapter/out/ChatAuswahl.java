package ch.urinet.archiv.ki.adapter.out;

import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import ch.urinet.archiv.ki.adapter.out.langchain4j.LangChain4jChatAgent;
import ch.urinet.archiv.ki.adapter.out.stub.ChatAgentStub;
import ch.urinet.archiv.ki.application.chat.ChatAgentPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Wählt den Chat-Agenten ({@code archiv.chat.stub}): Dev-Stub mit Demo-Folge ohne Request, sonst der Agent mit
 * LangChain4j gegen Infomaniak AI Services (Aufgabe 7.2). Ohne Token geht auch dort kein Request hinaus, der Chat meldet
 * dann {@code fehler}.
 */
@ApplicationScoped
public class ChatAuswahl {

    @Produces
    @ApplicationScoped
    public ChatAgentPort chat(@ConfigProperty(name = "archiv.chat.stub") boolean stub, ChatAgentStub dev,
                              Instance<LangChain4jChatAgent> echt) {
        return stub ? dev : echt.get();
    }

    /** Für die Startprüfung der Port-Auswahl (Spec 01, Req 16.13). */
    @Produces
    @Dependent
    public PortAuswahl auswahl(@ConfigProperty(name = "archiv.chat.stub") boolean stub) {
        return new PortAuswahl("ChatAgentPort", stub, true, Optional.empty());
    }
}
