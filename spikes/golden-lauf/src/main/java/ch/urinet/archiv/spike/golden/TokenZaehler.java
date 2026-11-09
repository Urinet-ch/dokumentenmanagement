package ch.urinet.archiv.spike.golden;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Zählt die Tokens jeder Modellantwort, auch wenn der AI-Service sie danach nicht parsen kann (Req 7.3, Nachfrage).
 * KiModell registriert den Zähler explizit als Listener (das Standardmodell der Extension wird nicht verwendet).
 * Belege laufen nacheinander, daher ein Zähler je Beleg.
 */
@ApplicationScoped
public class TokenZaehler implements ChatModelListener {

    public record Stand(long ein, long aus, int antworten, int fehler) {
    }

    private final AtomicLong ein = new AtomicLong();
    private final AtomicLong aus = new AtomicLong();
    private final AtomicInteger antworten = new AtomicInteger();
    private final AtomicInteger fehler = new AtomicInteger();

    public void zuruecksetzen() {
        ein.set(0);
        aus.set(0);
        antworten.set(0);
        fehler.set(0);
    }

    public Stand stand() {
        return new Stand(ein.get(), aus.get(), antworten.get(), fehler.get());
    }

    @Override
    public void onResponse(ChatModelResponseContext kontext) {
        antworten.incrementAndGet();
        TokenUsage t = kontext.chatResponse() == null ? null : kontext.chatResponse().tokenUsage();
        if (t != null) {
            ein.addAndGet(t.inputTokenCount() == null ? 0 : t.inputTokenCount());
            aus.addAndGet(t.outputTokenCount() == null ? 0 : t.outputTokenCount());
        }
    }

    @Override
    public void onError(ChatModelErrorContext kontext) {
        fehler.incrementAndGet();
    }
}
