package ch.urinet.archiv.ki.domain.chat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Antwort eines Durchgangs, wie sie gespeichert wird (Requirement 5.4). */
public record ChatAntwort(String inhalt, List<WerkzeugAufruf> werkzeuge, List<Quelle> quellen,
                          Optional<ChatEreignis.Ende> tokens, ChatStatus status) {

    public ChatAntwort {
        Objects.requireNonNull(inhalt, "inhalt");
        werkzeuge = List.copyOf(werkzeuge);
        quellen = List.copyOf(quellen);
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(status, "status");
    }
}
