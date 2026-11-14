package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.chat.ChatAnfrage;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.ki.domain.chat.ChatRolle;
import ch.urinet.archiv.ki.domain.chat.VerlaufEintrag;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Stand eines Durchgangs: Gesprächsspeicher aus dem Verlauf, Tokens, genau ein Ende, nichts nach Abbruch. */
class AgentLaufTest {

    private final List<ChatEreignis> ereignisse = new ArrayList<>();
    private final ChatAbbruch abbruch = new ChatAbbruch();

    private AgentLauf lauf(List<VerlaufEintrag> verlauf, int fenster) {
        Benutzer benutzer = new Benutzer("u1", Rolle.INHABER);
        ChatAnfrage anfrage = new ChatAnfrage(new MandantCode("urinet"), benutzer, Sichtregel.fuer(benutzer),
                Optional.empty(), "UriNet", "Frage", verlauf);
        return new AgentLauf(UUID.randomUUID(), anfrage, ereignisse::add, abbruch, fenster);
    }

    private static List<VerlaufEintrag> verlauf(int nachrichten) {
        List<VerlaufEintrag> liste = new ArrayList<>();
        for (int i = 0; i < nachrichten; i++) {
            liste.add(new VerlaufEintrag(i % 2 == 0 ? ChatRolle.BENUTZER : ChatRolle.ASSISTENT, "N" + i));
        }
        return liste;
    }

    @Test
    void speicherBeginntMitDemVerlaufNurFragenUndAntworten() {
        List<ChatMessage> nachrichten = lauf(verlauf(4), 10).speicher().messages();
        assertThat(nachrichten).hasSize(4);
        assertThat(nachrichten.get(0)).isInstanceOf(UserMessage.class);
        assertThat(nachrichten.get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) nachrichten.get(1)).hasToolExecutionRequests()).isFalse();
    }

    @Test
    void hoechstensDasFensterDerLetztenNachrichtenUndBeginntMitEinerFrage() {
        // 14 Nachrichten, Fenster 10: die letzten 10 sind N4 (Frage) bis N13 (Antwort)
        List<ChatMessage> nachrichten = lauf(verlauf(14), 10).speicher().messages();
        assertThat(nachrichten).hasSize(10);
        assertThat(((UserMessage) nachrichten.getFirst()).singleText()).isEqualTo("N4");
        // 13 Nachrichten, Fenster 10: die letzten 10 beginnen mit einer Antwort (N3), die entfällt
        assertThat(lauf(verlauf(13), 10).speicher().messages()).hasSize(9);
    }

    @Test
    void endeMeldetDieSummeUndNurEinmal() {
        AgentLauf lauf = lauf(List.of(), 10);
        lauf.tokens(100, 20);
        lauf.tokens(150, 30);
        lauf.ende();
        lauf.ende();
        lauf.ausfall("Spaet");
        lauf.melden(new ChatEreignis.Text("zu spät"));
        assertThat(ereignisse).containsExactly(new ChatEreignis.Ende(250, 50));
    }

    @Test
    void nachEinemAusfallKeinEndeUndKeinText() {
        AgentLauf lauf = lauf(List.of(), 10);
        lauf.ausfall("IOException");
        lauf.ende();
        lauf.melden(new ChatEreignis.Text("zu spät"));
        assertThat(ereignisse).containsExactly(new ChatEreignis.Ausfall("IOException"));
    }

    @Test
    void nachDemAbbruchMeldetNichtsMehrUndWartenEndet() throws Exception {
        AgentLauf lauf = lauf(List.of(), 10);
        abbruch.setzen();
        lauf.melden(new ChatEreignis.Text("x"));
        lauf.ende();
        lauf.warten(Duration.ofSeconds(5));
        assertThat(ereignisse).isEmpty();
    }

    @Test
    void wartenEndetMitAusfallNachDerGesamtfrist() throws Exception {
        AgentLauf lauf = lauf(List.of(), 10);
        lauf.warten(Duration.ofMillis(100));
        assertThat(ereignisse).containsExactly(new ChatEreignis.Ausfall("Zeitueberschreitung"));
    }

    @Test
    void rundenZaehlenHoch() {
        AgentLauf lauf = lauf(List.of(), 10);
        assertThat(lauf.runde()).isZero();
        assertThat(lauf.rundeBeginnt()).isEqualTo(1);
        assertThat(lauf.rundeBeginnt()).isEqualTo(2);
        assertThat(lauf.runde()).isEqualTo(2);
        assertThat(lauf.tokens()).containsExactly(0, 0);
    }
}
