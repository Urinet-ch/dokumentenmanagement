package ch.urinet.archiv.ki.adapter.out.langchain4j;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.function.Supplier;

/**
 * Gesprächsspeicher des Chat-Agenten (Spec 02, Aufgabe 7.2; design.md «Chat-Agent», Gesprächsspeicher): Je Durchgang
 * ein Speicher aus dem Verlauf, den die Chat-Schnittstelle übergibt (die letzten {@code archiv.ki.chat.fenster}
 * Nachrichten, nur Fragen und Antworten). Werkzeugaufrufe und -ergebnisse bleiben im Speicher des Durchgangs und gehen
 * nicht in den Verlauf späterer Fragen: Der Speicher wird mit dem Durchgang verworfen, und die Chat-Schnittstelle
 * speichert nur Fragen und Antworten. Eine ID ohne laufenden Durchgang bekommt keinen Speicher.
 */
@ApplicationScoped
public class AgentSpeicher implements Supplier<ChatMemoryProvider> {

    private final AgentLaeufe laeufe;

    AgentSpeicher(AgentLaeufe laeufe) {
        this.laeufe = laeufe;
    }

    @Override
    public ChatMemoryProvider get() {
        return id -> {
            ChatMemory speicher = laeufe.finden(id).map(AgentLauf::speicher).orElse(null);
            if (speicher == null) {
                throw new IllegalStateException("kein laufender Durchgang für den Gesprächsspeicher");
            }
            return speicher;
        };
    }
}
