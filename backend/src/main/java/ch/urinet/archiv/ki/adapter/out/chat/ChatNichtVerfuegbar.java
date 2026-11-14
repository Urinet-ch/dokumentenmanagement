package ch.urinet.archiv.ki.adapter.out.chat;

import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.chat.ChatAgentPort;
import ch.urinet.archiv.ki.application.chat.ChatAnfrage;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.util.function.Consumer;

/**
 * {@link ChatAgentPort} ohne Agenten, bis Aufgabe 7.2 den LangChain4j-Adapter bringt: meldet sofort
 * {@link ChatEreignis.Ausfall}, ohne Request. Der Client erhält {@code fehler} mit dem Wortlaut aus design.md «Error
 * Handling»; die Frage zählt trotzdem für das Rate-Limit.
 */
@ApplicationScoped
@Typed(ChatNichtVerfuegbar.class)
public class ChatNichtVerfuegbar implements ChatAgentPort {

    @Override
    public void beantworten(ChatAnfrage anfrage, Consumer<ChatEreignis> ausgabe, ChatAbbruch abbruch) {
        ausgabe.accept(new ChatEreignis.Ausfall(ChatNichtVerfuegbar.class.getSimpleName()));
    }
}
