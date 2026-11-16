package ch.urinet.archiv.integration.domain.webseite;

import java.util.Objects;

/** Reihe (Dokumentart) in der Webseiten-Schnittstelle: Code und Name (Spec 03, Req 5.8, 5.11). */
public record WebseiteReihe(String code, String name) {

    public WebseiteReihe {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
    }
}
