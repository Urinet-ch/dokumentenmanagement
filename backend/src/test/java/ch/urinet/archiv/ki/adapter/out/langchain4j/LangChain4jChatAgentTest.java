package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.chat.ChatAnfrage;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontextRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    @Test
    void modellaufrufNachGesprächsendeErfasstKeineTokens() {
        LangChain4jChatAgent agent = agent(5);
        AgentLauf lauf = lauf();
        lauf.beenden();

        agent.modellaufruf(lauf, null, false);

        assertThat(lauf.tokens()).containsExactly(0L, 0L);
    }
}
