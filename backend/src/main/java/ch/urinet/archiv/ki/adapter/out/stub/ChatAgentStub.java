package ch.urinet.archiv.ki.adapter.out.stub;

import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.chat.ChatAgentPort;
import ch.urinet.archiv.ki.application.chat.ChatAnfrage;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Dev-Stub des {@link ChatAgentPort} ({@code archiv.chat.stub=true}, Profile {@code dev} und {@code lokal}): eine feste
 * Demo-Folge ohne Sprachmodell und ohne Request, ohne die Frage zu lesen. Der echte Agent folgt mit Aufgabe 7.2.
 */
@ApplicationScoped
@Typed(ChatAgentStub.class)
public class ChatAgentStub implements ChatAgentPort {

    static final List<ChatEreignis> FOLGE = List.of(
            new ChatEreignis.WerkzeugStart("dokumente_suchen"),
            new ChatEreignis.WerkzeugErgebnis("dokumente_suchen", Map.of("frage", "Demo"), List.of(), Optional.empty()),
            new ChatEreignis.Text("Das ist eine Demo-Antwort ohne Sprachmodell. "),
            new ChatEreignis.Text("Der Chat-Agent folgt mit Aufgabe 7.2."),
            new ChatEreignis.Ende(0, 0));

    @Override
    public void beantworten(ChatAnfrage anfrage, Consumer<ChatEreignis> ausgabe, ChatAbbruch abbruch) {
        for (ChatEreignis ereignis : FOLGE) {
            if (abbruch.gesetzt()) {
                return;
            }
            ausgabe.accept(ereignis);
        }
    }
}
