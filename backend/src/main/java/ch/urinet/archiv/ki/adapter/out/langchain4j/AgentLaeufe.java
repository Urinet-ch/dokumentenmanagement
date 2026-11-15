package ch.urinet.archiv.ki.adapter.out.langchain4j;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Die laufenden Durchgänge des Chat-Agenten unter ihrer Lauf-ID. Die ID ist die {@code @MemoryId} des AI-Service und
 * damit die Lauf-ID der Werkzeuge ({@code WerkzeugKontextRegister}); Gesprächsspeicher, Werkzeug-Auswahl und Rundenzähler
 * finden den Durchgang hier, weil ihre Aufrufer auf fremden Threads ohne Request-Kontext laufen.
 */
@ApplicationScoped
public class AgentLaeufe {

    private final Map<UUID, AgentLauf> laeufe = new ConcurrentHashMap<>();

    void festhalten(AgentLauf lauf) {
        laeufe.put(lauf.id(), lauf);
    }

    Optional<AgentLauf> finden(Object id) {
        return id instanceof UUID uuid ? Optional.ofNullable(laeufe.get(uuid)) : Optional.empty();
    }

    void entfernen(UUID id) {
        laeufe.remove(id);
    }
}
