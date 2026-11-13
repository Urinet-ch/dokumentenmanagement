package ch.urinet.archiv.ki.domain;

import java.util.Objects;

/** Dokumentart, wie sie der Prompt nennt: Code, Name und Standard-Vertraulichkeit (Req 7.1, 7.2). */
public record ArtEintrag(String code, String name, String vertraulichkeit) {

    public ArtEintrag {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(vertraulichkeit, "vertraulichkeit");
    }
}
