package ch.urinet.archiv.ki.application.chat;

import java.util.List;
import java.util.UUID;

/** Gespeicherter Kontext einer eigenen Chatantwort für die Analyseprüfung. */
public record ChatAnalyseDaten(String inhalt, String status, boolean vollstaendig, List<Werkzeug> werkzeuge,
                               List<Quelle> quellen) {
    public ChatAnalyseDaten {
        werkzeuge = List.copyOf(werkzeuge);
        quellen = List.copyOf(quellen);
    }

    public record Werkzeug(String name, String kontextstufe) {
    }

    public record Quelle(UUID dokumentId, String seitenPfad) {
    }
}
