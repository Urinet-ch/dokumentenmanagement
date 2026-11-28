package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.chat.ChatAnfrage;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.ki.domain.chat.ChatRolle;
import ch.urinet.archiv.ki.domain.chat.VerlaufEintrag;
import ch.urinet.archiv.mandant.domain.MandantCode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.response.StreamingHandle;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Stand eines Durchgangs des Chat-Agenten (eine Frage): Anfrage, Ausgabe, Abbruch, Runden und Tokens. Callbacks des
 * Streamings und Werkzeugaufrufe laufen auf fremden Threads; alles, was an {@code ausgabe} geht, läuft darum unter einer
 * Sperre und nur bis zum ersten Ende ({@link #ende()}, {@link #ausfall}, Abbruch): Ereignisse danach zählen nicht
 * (Port-Vertrag). Der Gesprächsspeicher des Durchgangs enthält den Verlauf der letzten Fragen und Antworten und danach
 * die Nachrichten dieses Durchgangs, auch Werkzeugaufrufe und -ergebnisse; spätere Fragen erhalten sie nicht
 * (design.md «Chat-Agent», Gesprächsspeicher).
 */
final class AgentLauf {

    /** Reserve für Werkzeugnachrichten des Durchgangs im Speicher (je Runde Aufruf und Ergebnisse). */
    private static final int REICHWEITE_DURCHGANG = 80;
    /** Grund im {@link ChatEreignis.Ausfall}, wenn Wissen während des Durchgangs wegfällt (Req 22.9). */
    static final String WISSEN_AUSGESCHALTET = "WissenAusgeschaltet";

    private final UUID id;
    private final ChatAnfrage anfrage;
    private final MandantCode mandant;
    private final Consumer<ChatEreignis> ausgabe;
    private final ChatAbbruch abbruch;
    private final CompletableFuture<Void> fertig = new CompletableFuture<>();
    private final ChatMemory speicher;
    private int runde;
    private long tokensEin;
    private long tokensAus;
    private boolean beendet;
    private boolean regulaer;
    private volatile StreamingHandle handle;
    /** Nur wenn Wissen zu Beginn freigeschaltet war: gilt die Freischaltung noch (Spec 04, Req 22.9)? */
    private volatile BooleanSupplier wissenNochFrei;

    AgentLauf(UUID id, ChatAnfrage anfrage, Consumer<ChatEreignis> ausgabe, ChatAbbruch abbruch, int fenster) {
        this(id, anfrage, anfrage.mandant(), anfrage.verlauf(), ausgabe, abbruch, fenster);
    }

    /** Durchgang des öffentlichen Chatbots (Spec 03, 11.3): kein Benutzer, nur Mandant und Verlauf. */
    AgentLauf(UUID id, MandantCode mandant, List<VerlaufEintrag> verlauf, Consumer<ChatEreignis> ausgabe,
              ChatAbbruch abbruch, int fenster) {
        this(id, null, mandant, verlauf, ausgabe, abbruch, fenster);
    }

    private AgentLauf(UUID id, ChatAnfrage anfrage, MandantCode mandant, List<VerlaufEintrag> verlauf,
                      Consumer<ChatEreignis> ausgabe, ChatAbbruch abbruch, int fenster) {
        this.id = id;
        this.anfrage = anfrage;
        this.mandant = mandant;
        this.ausgabe = ausgabe;
        this.abbruch = abbruch;
        this.speicher = MessageWindowChatMemory.withMaxMessages(fenster + REICHWEITE_DURCHGANG);
        verlauf = VerlaufEintrag.normalisieren(verlauf.subList(Math.max(0, verlauf.size() - fenster), verlauf.size()));
        for (VerlaufEintrag e : verlauf) {
            speicher.add(e.rolle() == ChatRolle.BENUTZER ? UserMessage.from(e.inhalt()) : AiMessage.from(e.inhalt()));
        }
    }

    UUID id() {
        return id;
    }

    /**
     * Der Durchgang begann mit freigeschaltetem Wissen: Vor jedem weiteren Werkzeug und damit vor jeder weiteren
     * Modellrunde fragt {@link AgentWerkzeug} hier nach (Spec 04, Req 22.9).
     */
    void wissenBeobachten(BooleanSupplier nochFrei) {
        wissenNochFrei = nochFrei;
    }

    /** Wurde Wissen seit Beginn des Durchgangs abgeschaltet? */
    boolean wissenWeggefallen() {
        BooleanSupplier nochFrei = wissenNochFrei;
        return nochFrei != null && !nochFrei.getAsBoolean();
    }

    /** Wissen fiel während des Durchgangs weg: Ausfall mit festem Wortlaut, laufender Modellaufruf endet. */
    void wissenAusgeschaltet() {
        ausfall(WISSEN_AUSGESCHALTET, true);
    }

    ChatAnfrage anfrage() {
        return anfrage;
    }

    MandantCode mandant() {
        return mandant;
    }

    ChatMemory speicher() {
        return speicher;
    }

    boolean abgebrochen() {
        return abbruch.gesetzt();
    }

    /** Meldet ein Ereignis, solange der Durchgang läuft und kein Abbruch gesetzt ist. */
    synchronized void melden(ChatEreignis ereignis) {
        if (!beendet && !abbruch.gesetzt()) {
            ausgabe.accept(ereignis);
        }
    }

    /** Eine Modellantwort mit Werkzeugaufrufen liegt vor: zählt als Runde. */
    synchronized int rundeBeginnt() {
        return ++runde;
    }

    synchronized int runde() {
        return runde;
    }

    /** Tokens eines Modellaufrufs (jede Runde und die Schlussantwort). */
    synchronized void tokens(long ein, long aus) {
        tokensEin += ein;
        tokensAus += aus;
    }

    /** Tokens aller bisherigen Modellaufrufe dieses Durchgangs: {ein, aus}. */
    synchronized long[] tokens() {
        return new long[]{tokensEin, tokensAus};
    }

    void handle(StreamingHandle neu) {
        if (neu != null) {
            handle = neu;
        }
    }

    /** Die Antwort ist vollständig: {@link ChatEreignis.Ende} mit der Summe der Tokens aller Modellaufrufe. */
    synchronized void ende() {
        if (beendet) {
            return;
        }
        if (!abbruch.gesetzt()) {
            ausgabe.accept(new ChatEreignis.Ende(clamp(tokensEin), clamp(tokensAus)));
        }
        beendet = true;
        regulaer = true;
        fertig.complete(null);
    }

    /** Der Agent kann nicht (weiter) antworten; {@code grund} ist ein Klassenname. */
    synchronized void ausfall(String grund) {
        ausfall(grund, false);
    }

    /** Wie {@link #ausfall(String)}; mit {@code abbrechen} wird der laufende Modellaufruf zuerst beendet (Gesamtfrist). */
    private synchronized void ausfall(String grund, boolean abbrechen) {
        if (beendet) {
            return;
        }
        if (!abbruch.gesetzt()) {
            ausgabe.accept(new ChatEreignis.Ausfall(grund));
        }
        if (abbrechen) {
            // vor regulaer = true: sonst beendet beenden() den Stream nicht und die Callbacks laufen weiter
            streamAbbrechen();
        }
        beendet = true;
        regulaer = true;
        fertig.complete(null);
    }

    /**
     * Blockiert bis zum Ende, zum Abbruch oder zur Gesamtfrist. Bei Abbruch oder Frist wird der laufende Modellaufruf
     * beendet, soweit schon eine Antwort fliesst ({@link StreamingHandle#cancel()}); davor endet er an der Zeitgrenze des
     * Modells.
     */
    void warten(Duration gesamtfrist) throws InterruptedException {
        long ende = System.nanoTime() + gesamtfrist.toNanos();
        try {
            while (!fertig.isDone()) {
                if (abbruch.gesetzt()) {
                    return;
                }
                if (System.nanoTime() - ende > 0) {
                    ausfall("Zeitueberschreitung", true);
                    return;
                }
                try {
                    fertig.get(50, TimeUnit.MILLISECONDS);
                } catch (TimeoutException | ExecutionException e) {
                    // weiter prüfen
                }
            }
        } finally {
            beenden();
        }
    }

    /** Hält weitere Ereignisse an und beendet den laufenden Modellaufruf, falls er noch läuft. */
    synchronized void beenden() {
        boolean laeuft = !regulaer;
        beendet = true;
        if (laeuft) {
            streamAbbrechen();
        }
        fertig.complete(null);
    }

    /** Der Durchgang ist zu Ende (Antwort, Ausfall, Frist, Abbruch): keine weiteren Modellaufrufe und Werkzeuge. */
    synchronized boolean beendet() {
        return beendet;
    }

    private void streamAbbrechen() {
        StreamingHandle h = handle;
        if (h != null) {
            try {
                h.cancel();
            } catch (RuntimeException e) {
                // der Aufruf endet ohnehin
            }
        }
    }

    private static int clamp(long wert) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, wert));
    }
}
