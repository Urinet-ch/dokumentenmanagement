package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.chat.ChatAnfrage;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 04, Req 22.9 «vor dem nächsten Modellaufruf»: Das Modell der Chat-Durchgänge prüft vor jedem Aufruf, ob Wissen
 * noch freigeschaltet ist. Fällt es während der Werkzeuge weg, geht die nächste Runde nicht mehr hinaus; der Rückruf,
 * aus dem der AI-Service die nächste Runde startet, läuft auf dem Thread der Weitergabe.
 */
@Tag("req-22.9")
class EntkoppeltesModellWissenTest {

    private final AtomicInteger aufrufe = new AtomicInteger();
    private final List<ChatEreignis> ereignisse = new CopyOnWriteArrayList<>();
    private final ChatRequest anfrage = ChatRequest.builder().messages(UserMessage.from("Frage")).build();
    private final EntkoppeltesModell modell = new EntkoppeltesModell(new StreamingChatModel() {
        @Override
        public void doChat(ChatRequest a, StreamingChatResponseHandler handler) {
            aufrufe.incrementAndGet();
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Runde")).build());
        }
    });

    private AgentLauf lauf() {
        Benutzer benutzer = new Benutzer("u1", Rolle.INHABER);
        ChatAnfrage chat = new ChatAnfrage(new MandantCode("urinet"), benutzer, Sichtregel.fuer(benutzer),
                Optional.<IpAdresse>empty(), "UriNet", "Frage", List.of());
        return new AgentLauf(UUID.randomUUID(), chat, ereignisse::add, new ChatAbbruch(), 10);
    }

    /** Erste Runde; in ihrem Rückruf (wie nach den Werkzeugen) wird umgeschaltet und die nächste Runde gestartet. */
    private Throwable zweiRunden(AgentLauf lauf, Runnable nachDerErstenRunde) throws Exception {
        CompletableFuture<Throwable> zweite = new CompletableFuture<>();
        EntkoppeltesModell.imLauf(lauf, () -> modell.doChat(anfrage, new Handler() {
            @Override
            public void onCompleteResponse(ChatResponse antwort) {
                nachDerErstenRunde.run();
                modell.doChat(anfrage, new Handler() {
                    @Override
                    public void onCompleteResponse(ChatResponse antwort) {
                        zweite.complete(null);
                    }

                    @Override
                    public void onError(Throwable fehler) {
                        zweite.complete(fehler);
                    }
                });
            }

            @Override
            public void onError(Throwable fehler) {
                zweite.completeExceptionally(fehler);
            }
        }));
        return zweite.get(5, TimeUnit.SECONDS);
    }

    @Test
    void nachDemAbschaltenWaehrendDerWerkzeugeGehtKeinWeitererModellaufrufHinaus() throws Exception {
        AtomicBoolean frei = new AtomicBoolean(true);
        AgentLauf lauf = lauf();
        lauf.wissenBeobachten(frei::get);

        Throwable fehler = zweiRunden(lauf, () -> frei.set(false));

        assertThat(fehler).isInstanceOf(AgentWerkzeug.Abgebrochen.class);
        assertThat(aufrufe).hasValue(1);
        assertThat(ereignisse).containsExactly(new ChatEreignis.Ausfall(AgentLauf.WISSEN_AUSGESCHALTET));
        assertThat(lauf.beendet()).isTrue();
    }

    @Test
    void solangeWissenFreigeschaltetBleibtLaeuftDieNaechsteRunde() throws Exception {
        AgentLauf lauf = lauf();
        lauf.wissenBeobachten(() -> true);

        assertThat(zweiRunden(lauf, () -> { })).isNull();
        assertThat(aufrufe).hasValue(2);
        assertThat(ereignisse).isEmpty();
    }

    private abstract static class Handler implements StreamingChatResponseHandler {
        @Override
        public void onPartialResponse(String teil) {
        }
    }
}
