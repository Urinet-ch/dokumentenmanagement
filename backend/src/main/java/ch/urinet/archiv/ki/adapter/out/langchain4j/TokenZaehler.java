package ch.urinet.archiv.ki.adapter.out.langchain4j;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Zählt die Tokens der Modellantwort, auch wenn der AI-Service sie danach nicht parsen kann (Req 7.3, 7.8), wie im
 * Golden-Lauf; je Thread, weil der Worker mehrere Aufträge parallel ausführen kann und das Modell synchron im Thread
 * des Aufrufers antwortet.
 */
@ApplicationScoped
public class TokenZaehler implements ChatModelListener {

    /** Tokens eines Aufrufs; {@code antworten} 0, wenn keine Antwort kam. */
    public record Stand(long ein, long aus, int antworten) {
    }

    private final ThreadLocal<long[]> stand = ThreadLocal.withInitial(() -> new long[3]);

    public void zuruecksetzen() {
        stand.remove();
    }

    public Stand stand() {
        long[] s = stand.get();
        return new Stand(s[0], s[1], (int) s[2]);
    }

    @Override
    public void onResponse(ChatModelResponseContext kontext) {
        long[] s = stand.get();
        s[2]++;
        TokenUsage t = kontext.chatResponse() == null ? null : kontext.chatResponse().tokenUsage();
        if (t != null) {
            s[0] += t.inputTokenCount() == null ? 0 : t.inputTokenCount();
            s[1] += t.outputTokenCount() == null ? 0 : t.outputTokenCount();
        }
    }
}
