package ch.urinet.archiv.ki.domain.chat;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Nachricht eines Gesprächs für den Verlauf (design.md «Vertrag Welle 7», Verlauf). */
public record ChatNachricht(ChatRolle rolle, String inhalt, List<Quelle> quellen, Optional<ChatStatus> status,
                            Instant zeit) {

    public ChatNachricht {
        Objects.requireNonNull(rolle, "rolle");
        Objects.requireNonNull(inhalt, "inhalt");
        quellen = List.copyOf(quellen);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(zeit, "zeit");
    }
}
