package ch.urinet.archiv.sicherheit.domain;

import java.util.Objects;

/**
 * Angemeldeter Benutzer mit seiner wirksamen Archiv-Rolle (der stärksten, Req 2.2). {@code id} ist die Keycloak-ID
 * (Claim {@code sub}, {@code quarkus.oidc.token.principal-claim=sub}), nie der Benutzername: {@code erstellt_von} und
 * {@code person_benutzer} steuern die Sichtbarkeit von {@code PERSONAL} (Req 2.21, 2.24), und ein Benutzername kann neu
 * vergeben oder umbenannt werden. Der Benutzername dient nur zur Anzeige.
 */
public record Benutzer(String id, Rolle rolle) {

    public Benutzer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rolle, "rolle");
    }
}
