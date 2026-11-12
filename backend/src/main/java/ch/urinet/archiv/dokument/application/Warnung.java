package ch.urinet.archiv.dokument.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Hinweis im Eingangskorb, der die Bestätigung nicht verhindert (Req 8.10). {@code vorhandenId} fehlt, wenn der
 * Benutzer das vorhandene Dokument nicht sehen darf (Req 2.3).
 */
public record Warnung(String code, UUID vorhandenId) {

    public static final String BELEGNUMMER_BEKANNT = "BELEGNUMMER_BEKANNT";

    public Warnung {
        Objects.requireNonNull(code, "code");
    }
}
