package ch.urinet.archiv.ki.domain.chat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Antwort eines Durchgangs, wie sie gespeichert wird (Requirement 5.4). */
public record ChatAntwort(String inhalt, List<WerkzeugAufruf> werkzeuge, List<Quelle> quellen,
                          Optional<ChatEreignis.Ende> tokens, ChatStatus status, boolean kontextVollstaendig,
                          Long zeitBisErstemTextMs, String modellkennung, String systempromptVersion) {

    public ChatAntwort {
        Objects.requireNonNull(inhalt, "inhalt");
        werkzeuge = List.copyOf(werkzeuge);
        quellen = List.copyOf(quellen);
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(status, "status");
    }

    public ChatAntwort(String inhalt, List<WerkzeugAufruf> werkzeuge, List<Quelle> quellen,
                       Optional<ChatEreignis.Ende> tokens, ChatStatus status) {
        this(inhalt, werkzeuge, quellen, tokens, status, false, null, null, null);
    }

    public ChatAntwort(String inhalt, List<WerkzeugAufruf> werkzeuge, List<Quelle> quellen,
                       Optional<ChatEreignis.Ende> tokens, ChatStatus status, boolean kontextVollstaendig) {
        this(inhalt, werkzeuge, quellen, tokens, status, kontextVollstaendig, null, null, null);
    }

    public ChatAntwort mitExportdaten(Long zeitBisErstemTextMs, String modellkennung, String systempromptVersion) {
        return new ChatAntwort(inhalt, werkzeuge, quellen, tokens, status, kontextVollstaendig,
                zeitBisErstemTextMs, modellkennung, systempromptVersion);
    }
}
