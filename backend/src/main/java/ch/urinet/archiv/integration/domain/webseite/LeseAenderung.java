package ch.urinet.archiv.integration.domain.webseite;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Eintrag des Lese-Feeds: nur ID, Art, Stufe und Zeit, nie Titel oder Inhalt (Spec 03, Req 14.1). {@code stufe} ist
 * die tiefere der sichtbaren Stufen vor und nach der Änderung und nie über der Stufe des Schlüssels; ab ihr aufwärts
 * liegt das Dokument im Cache der Webseite.
 */
public record LeseAenderung(UUID dokumentId, AenderungArt art, Zugriffsstufe stufe, Instant zeit) {

    public LeseAenderung {
        Objects.requireNonNull(dokumentId, "dokumentId");
        Objects.requireNonNull(art, "art");
        Objects.requireNonNull(stufe, "stufe");
        Objects.requireNonNull(zeit, "zeit");
    }

    /**
     * Bewertet einen Eintrag der Tabelle für einen Schlüssel der Stufe {@code hoechste}; leer, wenn das Dokument weder
     * vorher noch nachher bis zu dieser Stufe sichtbar war (dann gehört der Eintrag nicht in den Feed).
     *
     * @param vorher  öffentliche Stufe vor der Änderung, {@code null} wenn nicht sichtbar
     * @param nachher öffentliche Stufe nach der Änderung, {@code null} wenn nicht sichtbar
     */
    public static Optional<LeseAenderung> bewerten(UUID dokumentId, Zugriffsstufe vorher, Zugriffsstufe nachher,
                                                   Zugriffsstufe hoechste, Instant zeit) {
        boolean vorherSichtbar = vorher != null && !vorher.hoeherAls(hoechste);
        boolean nachherSichtbar = nachher != null && !nachher.hoeherAls(hoechste);
        if (vorherSichtbar && nachherSichtbar) {
            return Optional.of(new LeseAenderung(dokumentId, AenderungArt.GEAENDERT,
                    Zugriffsstufe.kleinere(vorher, nachher), zeit));
        }
        if (nachherSichtbar) {
            return Optional.of(new LeseAenderung(dokumentId, AenderungArt.SICHTBAR, nachher, zeit));
        }
        if (vorherSichtbar) {
            return Optional.of(new LeseAenderung(dokumentId, AenderungArt.NICHT_SICHTBAR, vorher, zeit));
        }
        return Optional.empty();
    }
}
