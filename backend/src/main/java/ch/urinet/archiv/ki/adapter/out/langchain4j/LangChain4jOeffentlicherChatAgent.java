package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.oeffentlich.MandantBudget;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicheAnfrage;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicherChatAgentPort;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicherKontext;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicherKontextRegister;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.ki.domain.oeffentlich.OeffentlicherSystemPrompt;
import ch.urinet.archiv.mandant.application.MandantKontext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.TokenStream;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Adapter des {@link OeffentlicherChatAgentPort} mit dem {@link OeffentlicherAgent} (Spec 03, Aufgabe 11.3): hält den
 * Kontext der Anfrage (Mandant, {@code Lesezugriff}) im {@link OeffentlicherKontextRegister} fest, führt den Agenten aus
 * und bildet das Streaming auf {@link ChatEreignis} ab, wie {@link LangChain4jChatAgent} für den internen Chat. Je
 * Modellaufruf eine Zeile {@code ki_verbrauch} (Zweck {@code CHAT}) und der Verbrauch im Monatsbudget
 * ({@link MandantBudget}); auch ein Durchgang, der später scheitert, kostet, was er gekostet hat. Am Ende wird der Kontext
 * entfernt, auch bei Fehler oder Abbruch. Ohne Token geht kein Request hinaus ({@link ChatModell#bereit()}): sofort
 * {@code Ausfall}. Nie Frage, Antwort oder Parameter loggen.
 */
@ApplicationScoped
public class LangChain4jOeffentlicherChatAgent implements OeffentlicherChatAgentPort {

    private static final Logger LOG = Logger.getLogger(LangChain4jOeffentlicherChatAgent.class);

    private final OeffentlicherAgent agent;
    private final ChatModell modell;
    private final AgentLaeufe laeufe;
    private final OeffentlicherKontextRegister register;
    private final KiVerbrauchPort verbrauch;
    private final MandantBudget budget;
    private final MandantKontext mandanten;
    private final Counter nutzungSchreibfehler;
    private final Clock uhr;
    private final int runden;
    private final int fenster;
    private final Duration zeitgrenze;

    public LangChain4jOeffentlicherChatAgent(OeffentlicherAgent agent, ChatModell modell, AgentLaeufe laeufe,
                                             OeffentlicherKontextRegister register, KiVerbrauchPort verbrauch,
                                             MandantBudget budget, MandantKontext mandanten,
                                             MeterRegistry registry,
                                             @ConfigProperty(name = "archiv.webseite.chat.runden") int runden,
                                             @ConfigProperty(name = "archiv.webseite.chat.fenster") int fenster,
                                             @ConfigProperty(name = "archiv.ki.chat.zeitgrenze") Duration zeitgrenze) {
        if (runden < 1 || runden >= ArchivAgent.MAX_RUNDEN) {
            throw new IllegalArgumentException("archiv.webseite.chat.runden muss zwischen 1 und "
                    + (ArchivAgent.MAX_RUNDEN - 1) + " liegen");
        }
        this.agent = agent;
        this.modell = modell;
        this.laeufe = laeufe;
        this.register = register;
        this.verbrauch = verbrauch;
        this.budget = budget;
        this.mandanten = mandanten;
        nutzungSchreibfehler = Counter.builder("archiv.oeffentlicher_chat.nutzung_schreibfehler").register(registry);
        this.uhr = Clock.systemUTC();
        this.runden = runden;
        this.fenster = fenster;
        this.zeitgrenze = zeitgrenze;
    }

    @Override
    public void beantworten(OeffentlicheAnfrage anfrage, Consumer<ChatEreignis> ausgabe, ChatAbbruch abbruch) {
        if (abbruch.gesetzt()) {
            return;
        }
        if (!modell.bereit()) {
            ausgabe.accept(new ChatEreignis.Ausfall("OhneZugang"));
            return;
        }
        UUID id = UUID.randomUUID();
        AgentLauf lauf = new AgentLauf(id, anfrage.mandant(), anfrage.verlauf(), ausgabe, abbruch, fenster);
        laeufe.festhalten(lauf);
        register.festhalten(id, new OeffentlicherKontext(anfrage.mandant(), anfrage.zugriff()));
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

    private void starten(AgentLauf lauf, OeffentlicheAnfrage anfrage) {
        LOG.infof("Öffentlicher Chat-Durchgang mit Prompt %s", OeffentlicherSystemPrompt.kennung());
        String prompt = OeffentlicherSystemPrompt.system(anfrage.mandantenname(), uhr.instant());
        TokenStream strom = agent.antworten(lauf.id(), prompt, anfrage.frage());
        strom.onPartialResponseWithContext((teil, kontext) -> {
                    lauf.handle(kontext.streamingHandle());
                    if (!teil.text().isEmpty()) {
                        lauf.melden(new ChatEreignis.Text(teil.text()));
                    }
                })
                .onIntermediateResponse(antwort -> {
                    lauf.rundeBeginnt();
                    modellaufruf(lauf, anfrage, antwort, false);
                })
                .onCompleteResponse(antwort -> {
                    modellaufruf(lauf, anfrage, antwort, true);
                    lauf.ende();
                })
                .onError(fehler -> lauf.ausfall(KiFehler.grund(fehler)))
                .start();
    }

    /**
     * Tokens eines Modellaufrufs zählen, als Zeile {@code ki_verbrauch} und im Monatsbudget festhalten (nie Inhalte).
     * Die Schlussantwort trägt die Summe aller Runden; ihr eigener Anteil ist die Summe minus die Tokens der Runden davor
     * (wie im internen Chat, {@link LangChain4jChatAgent#modellaufruf}).
     */
    void modellaufruf(AgentLauf lauf, OeffentlicheAnfrage anfrage, ChatResponse antwort, boolean schluss) {
        if (lauf.beendet()) {
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
            nutzungSchreibfehler.increment();
            LOG.errorf("Verbrauch des öffentlichen Chats nicht geschrieben: %s", e.getClass().getSimpleName());
        }
        try {
            budget.verbrauchen(lauf.mandant(), anfrage.budgetMonat(), eigeneEin + eigeneAus);
        } catch (RuntimeException e) {
            nutzungSchreibfehler.increment();
            LOG.errorf("Budget des öffentlichen Chats nicht geschrieben: %s", e.getClass().getSimpleName());
        }
    }
}
