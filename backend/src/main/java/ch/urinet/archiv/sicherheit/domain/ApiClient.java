package ch.urinet.archiv.sicherheit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Eintrag in {@code public.api_client} ohne den Hash (Req 2.5, 2.20): an genau einen Mandanten gebunden; {@code aktiv}
 * ist nach dem Widerruf {@code false}, gelöscht wird nie.
 */
public record ApiClient(String id, String mandant, String name, ApiTokenScope scope, boolean aktiv, Instant erstellt,
                        Optional<Instant> zuletzt) {

    public ApiClient {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mandant, "mandant");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(erstellt, "erstellt");
        Objects.requireNonNull(zuletzt, "zuletzt");
    }
}
