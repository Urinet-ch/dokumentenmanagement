package ch.urinet.archiv.ki.domain.chat;

import java.util.Objects;

/** Frage oder Antwort aus dem Verlauf eines Gesprächs, ohne Werkzeugaufrufe (design.md «Chat-Agent», Gesprächsspeicher). */
public record VerlaufEintrag(ChatRolle rolle, String inhalt) {

    public VerlaufEintrag {
        Objects.requireNonNull(rolle, "rolle");
        Objects.requireNonNull(inhalt, "inhalt");
    }
}
