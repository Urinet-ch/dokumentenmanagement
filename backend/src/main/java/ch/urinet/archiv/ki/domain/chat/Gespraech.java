package ch.urinet.archiv.ki.domain.chat;

import java.util.List;
import java.util.Objects;

/** Ein eigenes Gespräch mit seinen Nachrichten in Reihenfolge. */
public record Gespraech(GespraechKopf kopf, List<ChatNachricht> nachrichten) {

    public Gespraech {
        Objects.requireNonNull(kopf, "kopf");
        nachrichten = List.copyOf(nachrichten);
    }
}
