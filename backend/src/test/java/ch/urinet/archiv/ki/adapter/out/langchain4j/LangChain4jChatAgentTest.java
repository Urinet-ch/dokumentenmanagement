package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.chat.ChatAnfrage;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontextRegister;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChain4jChatAgentTest {

    private static LangChain4jChatAgent agent(int runden) {
        return new LangChain4jChatAgent(null, null, null, new WerkzeugKontextRegister(), null, null,
                runden, 10, Duration.ofSeconds(1));
    }

    private static AgentLauf lauf() {
        Benutzer benutzer = new Benutzer("u1", Rolle.INHABER);
        ChatAnfrage anfrage = new ChatAnfrage(new MandantCode("urinet"), benutzer, Sichtregel.fuer(benutzer),
                Optional.empty(), "UriNet", "Frage", List.of());
        return new AgentLauf(UUID.randomUUID(), anfrage, ereignis -> {
        }, new ChatAbbruch(), 10);
    }

    @Test
    void rundenzahlMussUnterDerBibliotheksgrenzeLiegen() {
        assertThatCode(() -> agent(9)).doesNotThrowAnyException();
        assertThatThrownBy(() -> agent(10)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archiv.ki.chat.runden");
        assertThatThrownBy(() -> agent(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archiv.ki.chat.runden");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modellaufrufNachGespraechsendeErfasstKeineTokensUndKeineVerbrauchszeile(boolean schluss) {
        List<String> verbrauchszeilen = new ArrayList<>();
        KiVerbrauchPort verbrauch = (zweck, werte) -> verbrauchszeilen.add(zweck);
        LangChain4jChatAgent agent = new LangChain4jChatAgent(null, new ChatModell() {
            @Override
            public String modell() {
                return "test-modell";
            }
        }, null, new WerkzeugKontextRegister(), verbrauch, new MandantKontext(null, null, null) {
            @Override
            public <T> T mit(MandantCode code, Supplier<T> arbeit) {
                return arbeit.get();
            }
        }, 5, 10, Duration.ofSeconds(1));
        ChatResponse antwort = ChatResponse.builder().aiMessage(AiMessage.from("Antwort"))
                .tokenUsage(new TokenUsage(10, 5)).build();

        AgentLauf beendet = lauf();
        beendet.beenden();
        agent.modellaufruf(beendet, antwort, schluss);
        assertThat(beendet.tokens()).containsExactly(0L, 0L);
        assertThat(verbrauchszeilen).as("keine Verbrauchszeile nach dem Ende").isEmpty();

        AgentLauf laufend = lauf();
        agent.modellaufruf(laufend, antwort, schluss);
        assertThat(laufend.tokens()).as("Gegenprobe: ein laufendes Gespräch zählt").containsExactly(10L, 5L);
        assertThat(verbrauchszeilen).containsExactly(KiVerbrauchPort.CHAT);
    }
}
