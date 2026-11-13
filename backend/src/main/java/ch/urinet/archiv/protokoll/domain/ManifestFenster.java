package ch.urinet.archiv.protokoll.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Fenster {@code [von, bis)} des Manifests für das Datum {@code datum} (design.md «Manifest», Req 15.7): {@code bis} ist
 * der Beginn von {@code datum + 1} in Europe/Zurich, {@code von} das {@code bis} des Vorgängers; das erste Manifest
 * eines Mandanten hat {@code von = null}. Die Tagesgrenzen kommen aus {@code ZonedDateTime}, Umstellungstage haben
 * darum 23 bzw. 25 Stunden.
 */
public record ManifestFenster(LocalDate datum, Instant von, Instant bis) {

    public static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    public ManifestFenster {
        Objects.requireNonNull(datum, "datum");
        Objects.requireNonNull(bis, "bis");
        if (!bis.equals(bis(datum))) {
            throw new IllegalArgumentException("bis ist nicht der Beginn des Folgetags in Europe/Zurich");
        }
        if (von != null && !von.isBefore(bis)) {
            throw new IllegalArgumentException("von liegt nicht vor bis");
        }
    }

    /** Fenster für {@code datum}; {@code vorgaengerBis} ist {@code null} für das erste Manifest. */
    public static ManifestFenster fuer(LocalDate datum, Instant vorgaengerBis) {
        return new ManifestFenster(datum, vorgaengerBis, bis(datum));
    }

    public static Instant bis(LocalDate datum) {
        return datum.plusDays(1).atStartOfDay(ZUERICH).toInstant();
    }

    /** Kalendertag in Europe/Zurich, z. B. für das Anlagedatum des Mandanten. */
    public static LocalDate tag(Instant zeitpunkt) {
        return zeitpunkt.atZone(ZUERICH).toLocalDate();
    }

    public boolean erstes() {
        return von == null;
    }
}
