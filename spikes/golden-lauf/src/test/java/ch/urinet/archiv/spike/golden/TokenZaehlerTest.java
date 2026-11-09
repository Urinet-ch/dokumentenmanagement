package ch.urinet.archiv.spike.golden;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Tokens eines nicht parsebaren Erstaufrufs zählen mit; Aufrufe ohne Antwort werden gezählt, nicht geschätzt. */
class TokenZaehlerTest {

    static final ChatRequest ANFRAGE = ChatRequest.builder().messages(UserMessage.from("synthetisch")).build();

    static ChatModelResponseContext antwort(int ein, int aus) {
        ChatResponse r = ChatResponse.builder().aiMessage(AiMessage.from("{kaputt")).tokenUsage(new TokenUsage(ein, aus)).build();
        return new ChatModelResponseContext(r, ANFRAGE, ModelProvider.OTHER, new HashMap<>());
    }

    @Test
    void erstaufrufUndNachfrageWerdenSummiert() {
        TokenZaehler z = new TokenZaehler();
        z.onResponse(antwort(1000, 200));
        z.onResponse(antwort(1100, 250));
        assertThat(z.stand()).isEqualTo(new TokenZaehler.Stand(2100, 450, 2, 0));
    }

    @Test
    void fehlerOhneAntwortUndZuruecksetzen() {
        TokenZaehler z = new TokenZaehler();
        z.onResponse(antwort(10, 5));
        z.onError(new ChatModelErrorContext(new RuntimeException("Timeout"), ANFRAGE, ModelProvider.OTHER, new HashMap<>()));
        assertThat(z.stand()).isEqualTo(new TokenZaehler.Stand(10, 5, 1, 1));
        z.zuruecksetzen();
        assertThat(z.stand()).isEqualTo(new TokenZaehler.Stand(0, 0, 0, 0));
    }
}
