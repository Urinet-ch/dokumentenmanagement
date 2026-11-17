package ch.urinet.archiv.sicherheit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Eintrag in {@code public.api_client} ohne den Hash (Req 2.5, 2.20): an genau einen Mandanten gebunden; {@code aktiv}
 * ist nach dem Widerruf {@code false}, gelöscht wird nie.
 */
public record ApiClient(String id, String mandant, String name, ApiTokenScope scope, boolean aktiv, Instant erstellt,
                        Optional<Instant> zuletzt, Optional<String> webseite, Optional<String> hoechsteStufe,
                        Optional<Instant> gueltigBis, boolean absenderPruefen) {

    public ApiClient {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mandant, "mandant");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(erstellt, "erstellt");
        Objects.requireNonNull(zuletzt, "zuletzt");
        Objects.requireNonNull(webseite, "webseite");
        Objects.requireNonNull(hoechsteStufe, "hoechsteStufe");
        Objects.requireNonNull(gueltigBis, "gueltigBis");
        if (scope.webseitenSchluessel() != webseite.isPresent()
                || scope.webseitenSchluessel() != hoechsteStufe.isPresent()
                || !scope.webseitenSchluessel() && absenderPruefen
                || webseite.filter(String::isBlank).isPresent()
                || hoechsteStufe.filter(s -> !s.equals("oeffentlich") && !s.equals("mitglieder")
                        && !s.equals("vorstand")).isPresent()
                || scope.istErfassung() && !hoechsteStufe.orElseThrow().equals("vorstand")) {
            throw new IllegalArgumentException("Webseiten-Angaben passen nicht zum Token-Scope");
        }
    }

    public ApiClient(String id, String mandant, String name, ApiTokenScope scope, boolean aktiv, Instant erstellt,
                     Optional<Instant> zuletzt) {
        this(id, mandant, name, scope, aktiv, erstellt, zuletzt, Optional.empty(), Optional.empty(), Optional.empty(),
                false);
    }
}
