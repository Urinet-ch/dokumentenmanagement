package ch.urinet.archiv.ablage.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Eine Seite der Versionsliste unter einem Präfix, nach Schlüssel und danach in der Reihenfolge des Object Storage.
 * {@code weiter} ist die Marke für die nächste Seite; leer, wenn die Liste zu Ende ist.
 */
public record Versionsseite(List<Objektversion> versionen, Optional<Marke> weiter) {

    public Versionsseite {
        versionen = List.copyOf(versionen);
        Objects.requireNonNull(weiter, "weiter");
    }

    /**
     * Fortsetzung nach Schlüssel und Versions-ID ({@code key-marker}, {@code version-id-marker}). Ohne Versions-ID
     * (der Object Storage hat keine {@code NextVersionIdMarker} geliefert) geht es nur mit dem {@code key-marker} weiter;
     * ein leerer {@code version-id-marker} wird nie gesendet.
     */
    public record Marke(String schluessel, Optional<String> versionId) {

        public Marke {
            Objects.requireNonNull(schluessel, "schluessel");
            Objects.requireNonNull(versionId, "versionId");
            if (versionId.filter(String::isEmpty).isPresent()) {
                throw new IllegalArgumentException("leere Versions-ID in der Marke");
            }
        }

        public Marke(String schluessel, String versionId) {
            this(schluessel, Optional.of(versionId));
        }
    }
}
