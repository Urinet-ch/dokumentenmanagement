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
     * Marke für die nächste Seite aus der Antwort des Object Storage ({@code IsTruncated}, {@code NextKeyMarker},
     * {@code NextVersionIdMarker}). Ist die Antwort abgeschnitten, aber fehlt einer der beiden Marker oder ist er leer,
     * lässt sich nicht sicher fortsetzen: Nur mit dem {@code key-marker} beginnt die nächste Seite nach allen Versionen
     * dieses Schlüssels, und dessen restliche Versionen und Löschmarken fielen still aus der Prüfung (Req 15.10).
     *
     * @throws UnsichereFortsetzung wenn die Liste abgeschnitten ist und ein Marker fehlt
     */
    public static Optional<Marke> weiter(String praefix, boolean abgeschnitten, String naechsterSchluessel,
                                         String naechsteVersionId) {
        if (!abgeschnitten) {
            return Optional.empty();
        }
        if (naechsterSchluessel == null || naechsterSchluessel.isEmpty() || naechsteVersionId == null
                || naechsteVersionId.isEmpty()) {
            throw new UnsichereFortsetzung(praefix, Optional.ofNullable(naechsterSchluessel)
                    .filter(s -> !s.isEmpty()));
        }
        return Optional.of(new Marke(naechsterSchluessel, naechsteVersionId));
    }

    /**
     * Fortsetzung nach Schlüssel und Versions-ID ({@code key-marker}, {@code version-id-marker}). Eine Marke ohne
     * Versions-ID entsteht nur noch beim Lesen alter Einträge im Register (Spalte {@code marke_version} leer); die
     * Adapter setzen damit nicht fort (siehe {@link #versionIdZumFortsetzen}).
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

        /**
         * Versions-ID für den {@code version-id-marker}.
         *
         * @throws UnsichereFortsetzung ohne Versions-ID, weil nur mit dem {@code key-marker} Versionen dieses
         *                              Schlüssels übersprungen würden
         */
        public String versionIdZumFortsetzen(String praefix) {
            return versionId.orElseThrow(() -> new UnsichereFortsetzung(praefix, Optional.of(schluessel)));
        }
    }
}
