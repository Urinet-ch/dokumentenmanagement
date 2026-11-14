package ch.urinet.archiv.ki.adapter.out;

import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import ch.urinet.archiv.ki.adapter.out.chat.ChatNichtVerfuegbar;
import ch.urinet.archiv.ki.adapter.out.stub.ChatAgentStub;
import ch.urinet.archiv.ki.application.chat.ChatAgentPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Wählt den Chat-Agenten ({@code archiv.chat.stub}): Dev-Stub mit Demo-Folge, sonst bis Aufgabe 7.2
 * {@link ChatNichtVerfuegbar}. In prod meldet die Startprüfung den fehlenden Agenten als Warnung, nicht als Abbruch.
 */
@ApplicationScoped
public class ChatAuswahl {

    static final String FOLGT_MIT = "Spec 02, Aufgabe 7.2";

    @Produces
    @ApplicationScoped
    public ChatAgentPort chat(@ConfigProperty(name = "archiv.chat.stub") boolean stub, ChatAgentStub dev,
                              ChatNichtVerfuegbar ohneAgent) {
        return stub ? dev : ohneAgent;
    }

    /** Für die Startprüfung der Port-Auswahl (Spec 01, Req 16.13). */
    @Produces
    @Dependent
    public PortAuswahl auswahl(@ConfigProperty(name = "archiv.chat.stub") boolean stub) {
        return new PortAuswahl("ChatAgentPort", stub, false, Optional.of(FOLGT_MIT));
    }
}
