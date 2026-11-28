package ch.urinet.archiv.ki.adapter.out.langchain4j;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entkoppelt die Rückrufe des Streamings vom Thread des HTTP-Clients (Spec 02, Aufgabe 7.2): Der Client der Bibliothek
 * ruft {@code onPartialResponse}, {@code onCompleteResponse} und {@code onError} auf einem Vert.x-Event-Loop-Thread auf;
 * dort führt der AI-Service auch die Werkzeuge aus und startet die nächste Runde. Blockierende Werkzeuge (JDBC,
 * Transaktionen) und das Schreiben von {@code ki_verbrauch} dürfen aber nicht auf einem Event-Loop-Thread laufen. Dieses
 * Modell leitet darum jeden Rückruf in Reihenfolge an einen eigenen Plattform-Thread weiter (einer je Modellaufruf, der
 * nach 5 s Leerlauf endet). Der {@link dev.langchain4j.model.chat.response.StreamingHandle} aus dem Kontext der Teilantwort
 * bleibt der des Clients; {@code cancel()} beendet den Aufruf wie sonst.
 */
final class EntkoppeltesModell implements StreamingChatModel {

    private static final AtomicInteger NUMMER = new AtomicInteger();
    /**
     * Gesetzt von {@link AgentWerkzeug} nach einem Abbruch auf dem Thread, der gleich die nächste Runde auslöst: Der
     * AI-Service macht aus einer Ausnahme des Werkzeugs ein Fehlerergebnis und ruft das Modell trotzdem noch einmal auf.
     */
    private static final ThreadLocal<Boolean> NAECHSTER_AUFRUF_ABGEBROCHEN = new ThreadLocal<>();

    /**
     * Durchgang, zu dem der laufende Thread gerade gehört: gesetzt beim Start ({@link #imLauf}) und von der
     * {@code Weitergabe} für jeden Rückruf, in dem der AI-Service Werkzeuge ausführt und die nächste Runde startet.
     */
    private static final ThreadLocal<AgentLauf> LAUF = new ThreadLocal<>();

    private final StreamingChatModel ziel;

    EntkoppeltesModell(StreamingChatModel ziel) {
        this.ziel = ziel;
    }

    /** Der nächste Modellaufruf dieses Threads geht nicht mehr hinaus (Abbruch durch den Client). */
    /** Nimmt eine gesetzte Verhinderung zurück (Tests räumen den ThreadLocal des Threads damit auf). */
    static void verhinderungZuruecksetzen() {
        NAECHSTER_AUFRUF_ABGEBROCHEN.remove();
    }

    static void naechstenAufrufVerhindern() {
        NAECHSTER_AUFRUF_ABGEBROCHEN.set(Boolean.TRUE);
    }

    /** Führt {@code arbeit} als Teil von {@code lauf} aus: Modellaufrufe darin gehören zu diesem Durchgang. */
    static void imLauf(AgentLauf lauf, Runnable arbeit) {
        AgentLauf vorher = LAUF.get();
        LAUF.set(lauf);
        try {
            arbeit.run();
        } finally {
            if (vorher == null) {
                LAUF.remove();
            } else {
                LAUF.set(vorher);
            }
        }
    }

    /**
     * Vor jedem Modellaufruf: nach einem Abbruch keiner mehr; ist Wissen seit Beginn des Durchgangs abgeschaltet
     * (Spec 04, Req 22.9), endet der Durchgang mit dem festen Ausfalltext, bevor der Aufruf hinausgeht.
     */
    @Override
    public void doChat(ChatRequest anfrage, StreamingChatResponseHandler handler) {
        if (Boolean.TRUE.equals(NAECHSTER_AUFRUF_ABGEBROCHEN.get())) {
            NAECHSTER_AUFRUF_ABGEBROCHEN.remove();
            handler.onError(new AgentWerkzeug.Abgebrochen());
            return;
        }
        AgentLauf lauf = LAUF.get();
        if (lauf != null && lauf.wissenWeggefallen()) {
            lauf.wissenAusgeschaltet();
            handler.onError(new AgentWerkzeug.Abgebrochen());
            return;
        }
        ziel.doChat(anfrage, new Weitergabe(handler, neuerThread(), lauf));
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return ziel.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return ziel.listeners();
    }

    @Override
    public ModelProvider provider() {
        return ziel.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return ziel.supportedCapabilities();
    }

    private static ExecutorService neuerThread() {
        return new ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread thread = new Thread(r, "chat-modell-" + NUMMER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Gibt jeden Rückruf in Reihenfolge auf dem eigenen Thread weiter; nach dem Ende wird der Thread frei. */
    private record Weitergabe(StreamingChatResponseHandler ziel, ExecutorService thread, AgentLauf lauf)
            implements StreamingChatResponseHandler {

        /** Jeder Rückruf läuft im Durchgang, damit die nächste Runde aus ihm heraus geprüft wird. */
        private void weiter(Runnable rueckruf) {
            thread.execute(() -> imLauf(lauf, rueckruf));
        }

        @Override
        public void onPartialResponse(String teil) {
            weiter(() -> ziel.onPartialResponse(teil));
        }

        @Override
        public void onPartialResponse(PartialResponse teil, PartialResponseContext kontext) {
            weiter(() -> ziel.onPartialResponse(teil, kontext));
        }

        @Override
        public void onPartialThinking(PartialThinking teil) {
            weiter(() -> ziel.onPartialThinking(teil));
        }

        @Override
        public void onPartialThinking(PartialThinking teil, PartialThinkingContext kontext) {
            weiter(() -> ziel.onPartialThinking(teil, kontext));
        }

        @Override
        public void onPartialToolCall(PartialToolCall teil) {
            weiter(() -> ziel.onPartialToolCall(teil));
        }

        @Override
        public void onPartialToolCall(PartialToolCall teil, PartialToolCallContext kontext) {
            weiter(() -> ziel.onPartialToolCall(teil, kontext));
        }

        @Override
        public void onCompleteToolCall(CompleteToolCall aufruf) {
            weiter(() -> ziel.onCompleteToolCall(aufruf));
        }

        @Override
        public void onUnmappedRawEvent(Object ereignis) {
            weiter(() -> ziel.onUnmappedRawEvent(ereignis));
        }

        @Override
        public void onCompleteResponse(ChatResponse antwort) {
            weiter(() -> ziel.onCompleteResponse(antwort));
            thread.shutdown();
        }

        @Override
        public void onError(Throwable fehler) {
            weiter(() -> ziel.onError(fehler));
            thread.shutdown();
        }
    }
}
