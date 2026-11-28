package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.chat.ChatAnfrage;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Zugang des Chat-Modells (gemeinsam mit {@link KiModell}) und kein Request ohne Token (Spec 02, Aufgabe 7.2, Req 16.21). */
class ChatModellTest {

    private static final String INFOMANIAK = "https://api.infomaniak.com/2/ai/123/openai/v1";

    private static ChatModell modell(String url, Optional<String> token) {
        ChatModell m = new ChatModell();
        m.basisUrl = url;
        m.modell = "mistralai/Mistral-Small-4-119B-2603";
        m.temperatur = 0;
        m.maxTokens = 1500;
        m.zeitgrenze = Duration.ofSeconds(20);
        m.token = token;
        return m;
    }

    @Test
    void ohneTokenAnInfomaniakNichtBereitUndKeinModell() {
        ChatModell m = modell(INFOMANIAK, Optional.empty());
        assertThat(m.bereit()).isFalse();
        assertThatThrownBy(m::get).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mitTokenOderMitLokalemZielBereit() {
        assertThat(modell(INFOMANIAK, Optional.of(" geheim\n")).bereit()).isTrue();
        assertThat(modell("http://localhost:8089/v1", Optional.empty()).bereit()).isTrue();
        assertThat(modell("https://localhost.example.ch/v1", Optional.of(" ")).bereit()).isFalse();
    }

    @Test
    void nenntDasModellFuerDenVerbrauch() {
        assertThat(modell(INFOMANIAK, Optional.of("geheim")).modell()).isEqualTo("mistralai/Mistral-Small-4-119B-2603");
    }

    @Test
    void derAgentMeldetOhneTokenSofortAusfallOhneRequest() {
        ChatModell m = modell(INFOMANIAK, Optional.empty());
        LangChain4jChatAgent agent = new LangChain4jChatAgent(null, m, new AgentLaeufe(), null, null, null, 5, 10,
                Duration.ofSeconds(20), null);
        Benutzer benutzer = new Benutzer("u1", Rolle.INHABER);
        List<ChatEreignis> ereignisse = new ArrayList<>();

        agent.beantworten(new ChatAnfrage(new MandantCode("urinet"), benutzer, Sichtregel.fuer(benutzer), Optional.empty(),
                "UriNet", "Frage", List.of()), ereignisse::add, new ChatAbbruch());

        assertThat(ereignisse).containsExactly(new ChatEreignis.Ausfall("OhneZugang"));
    }

    @Test
    void nachGesetztemAbbruchKeinEreignis() {
        ChatAbbruch abbruch = new ChatAbbruch();
        abbruch.setzen();
        LangChain4jChatAgent agent = new LangChain4jChatAgent(null, modell(INFOMANIAK, Optional.empty()), new AgentLaeufe(),
                null, null, null, 5, 10, Duration.ofSeconds(20), null);
        Benutzer benutzer = new Benutzer("u1", Rolle.INHABER);
        List<ChatEreignis> ereignisse = new ArrayList<>();

        agent.beantworten(new ChatAnfrage(new MandantCode("urinet"), benutzer, Sichtregel.fuer(benutzer), Optional.empty(),
                "UriNet", "Frage", List.of()), ereignisse::add, abbruch);

        assertThat(ereignisse).isEmpty();
    }
}
