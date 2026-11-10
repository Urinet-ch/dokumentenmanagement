package ch.urinet.archiv.sicherheit.domain;

import java.util.Objects;

/** Angemeldeter Benutzer mit genau einer Archiv-Rolle; {@code id} ist die Keycloak-ID ({@code sub}), nie ein Name. */
public record Benutzer(String id, Rolle rolle) {

    public Benutzer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rolle, "rolle");
    }
}
