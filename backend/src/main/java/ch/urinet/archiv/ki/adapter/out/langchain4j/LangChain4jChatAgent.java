package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.chat.ChatAgentPort;
import ch.urinet.archiv.ki.application.chat.ChatAnfrage;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontext;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontextRegister;
import ch.urinet.archiv.ki.domain.ChatSystemPrompt;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.mandant.application.MandantKontext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.TokenStream;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Adapter des {@link ChatAgentPort} mit dem {@link ArchivAgent} (Spec 02, Aufgabe 7.2; design.md «Chat-Agent»,
 * «Umsetzung (Aufgabe 7.2)»): hält den Kontext der Anfrage im {@link WerkzeugKontextRegister} fest, führt den Agenten
 * aus und bildet das Streaming auf {@link ChatEreignis} ab: {@code Text}-Stücke, {@code WerkzeugStart} und
 * {@code WerkzeugErgebnis} aus {@link AgentWerkzeug}, {@code Ende} mit der Summe der Tokens aller Modellaufrufe, bei einem
 * Fehler {@code Ausfall} mit dem Klassennamen. Je Modellaufruf (jede Werkzeug-Runde und die Schlussantwort) eine Zeile
 * {@code ki_verbrauch} mit Zweck {@code CHAT}, Modell und Tokens, ohne Inhalte; die Tokens kommen aus den Antworten des
 * Streams, nicht aus dem ThreadLocal von {@link TokenZaehler}. Am Ende wird der Kontext entfernt, auch bei Fehler oder
 * Abbruch. Ohne Token geht kein Request hinaus ({@link ChatModell#bereit()}): sofort {@code Ausfall}. Nie Frage,
 * Antwort oder Parameter loggen (Req 16.7). Nur über {@code ChatAuswahl} wählbar.
 */
@ApplicationScoped
@Typed(LangChain4jChatAgent.class)
public class LangChain4jChatAgent implements ChatAgentPort {

    private static final Logger LOG = Logger.getLogger(LangChain4jChatAgent.class);

    private final ArchivAgent agent;
    private final ChatModell modell;
    private final AgentLaeufe laeufe;
    private final WerkzeugKontextRegister register;
    private final KiVerbrauchPort verbrauch;
    private final MandantKontext mandanten;
    private final Clock uhr;
    private final int runden;
    private final int fenster;
    private final Duration zeitgrenze;

    public LangChain4jChatAgent(ArchivAgent agent, ChatModell modell, AgentLaeufe laeufe, WerkzeugKontextRegister register,
                         KiVerbrauchPort verbrauch, MandantKontext mandanten,
                         @ConfigProperty(name = "archiv.ki.chat.runden") int runden,
                         @ConfigProperty(name = "archiv.ki.chat.fenster") int fenster,
                         @ConfigProperty(name = "archiv.ki.chat.zeitgrenze") Duration zeitgrenze) {
        if (runden < 1 || runden >= ArchivAgent.MAX_RUNDEN) {
            throw new IllegalArgumentException("archiv.ki.chat.runden muss zwischen 1 und "
                    + (ArchivAgent.MAX_RUNDEN - 1) + " liegen (Obergrenze der Bibliothek: " + ArchivAgent.MAX_RUNDEN + ")");
        }
        this.agent = agent;
        this.modell = modell;
        this.laeufe = laeufe;
        this.register = register;
        this.verbrauch = verbrauch;
        this.mandanten = mandanten;
        this.uhr = Clock.systemUTC();
        this.runden = runden;
        this.fenster = fenster;
        this.zeitgrenze = zeitgrenze;
    }

    @Override
    public void beantworten(ChatAnfrage anfrage, Consumer<ChatEreignis> ausgabe, ChatAbbruch abbruch) {
        if (abbruch.gesetzt()) {
            return;
        }
        if (!modell.bereit()) {
            ausgabe.accept(new ChatEreignis.Ausfall("OhneZugang"));
            return;
        }
        UUID id = UUID.randomUUID();
        AgentLauf lauf = new AgentLauf(id, anfrage, ausgabe, abbruch, fenster);
        laeufe.festhalten(lauf);
        register.festhalten(id, new WerkzeugKontext(anfrage.mandant(), anfrage.benutzer(), anfrage.benutzer().rolle(),
                anfrage.sichtregel(), anfrage.ip()), anfrage.geseheneDokumente(), anfrage.nummerierteTreffer());
        try {
            starten(lauf, anfrage);
            // Obergrenze: jede Runde und die Schlussantwort je höchstens eine Zeitgrenze, dazu Reserve für Werkzeuge
            lauf.warten(zeitgrenze.multipliedBy(runden + 1L).plusSeconds(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lauf.ausfall(e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            lauf.ausfall(KiFehler.grund(e));
        } finally {
            lauf.beenden();
            register.entfernen(id);
            laeufe.entfernen(id);
        }
    }

    private void starten(AgentLauf lauf, ChatAnfrage anfrage) {
        LOG.infof("Chat-Durchgang mit Prompt %s", ChatSystemPrompt.kennung());
        String prompt = ChatSystemPrompt.system(anfrage.mandantenname(), uhr.instant());
        TokenStream strom = agent.antworten(lauf.id(), prompt, anfrage.frage());
        strom.onPartialResponseWithContext((teil, kontext) -> {
                    lauf.handle(kontext.streamingHandle());
                    if (!teil.text().isEmpty()) {
                        lauf.melden(new ChatEreignis.Text(teil.text()));
                    }
                })
                .onIntermediateResponse(antwort -> {
                    // Antwort mit Werkzeugaufrufen: eine Runde; die Zählung gilt für deren Aufrufe
                    lauf.rundeBeginnt();
                    modellaufruf(lauf, antwort, false);
                })
                .onCompleteResponse(antwort -> {
                    // die Schlussantwort trägt die Summe aller Runden; gezählt wird nur ihr eigener Aufruf
                    modellaufruf(lauf, antwort, true);
                    lauf.ende();
                })
                .onError(fehler -> lauf.ausfall(KiFehler.grund(fehler)))
                .start();
    }

    /**
     * Tokens eines Modellaufrufs zählen und als Zeile {@code ki_verbrauch} festhalten (nie Inhalte). Die Bibliothek
     * meldet in der Schlussantwort die Summe aller Aufrufe des Durchgangs (Werkzeug-Runden und Schlussantwort,
     * {@code AgentSchritte} prüft das); ihr eigener Anteil ist die Summe minus die Tokens der Runden davor. Ist die
     * Summe kleiner als die bisherigen Tokens, zählt sie als Wert des einzelnen Aufrufs.
     */
    void modellaufruf(AgentLauf lauf, ChatResponse antwort, boolean schluss) {
        if (lauf.beendet()) {
            // Frist oder Abbruch: Nachzügler zählen nicht mehr und schreiben keine Verbrauchszeile
            return;
        }
        TokenUsage nutzung = antwort == null ? null : antwort.tokenUsage();
        long ein = nutzung == null || nutzung.inputTokenCount() == null ? 0 : nutzung.inputTokenCount();
        long aus = nutzung == null || nutzung.outputTokenCount() == null ? 0 : nutzung.outputTokenCount();
        if (schluss) {
            long[] davor = lauf.tokens();
            ein = ein >= davor[0] ? ein - davor[0] : ein;
            aus = aus >= davor[1] ? aus - davor[1] : aus;
        }
        lauf.tokens(ein, aus);
        long eigeneEin = ein;
        long eigeneAus = aus;
        try {
            mandanten.mit(lauf.mandant(), () -> verbrauch.erfassen(KiVerbrauchPort.CHAT,
                    new KiPort.Verbrauch(modell.modell(), eigeneEin, eigeneAus)));
        } catch (RuntimeException e) {
            LOG.errorf("Verbrauch des Chats nicht geschrieben: %s", e.getClass().getSimpleName());
        }
    }
}
