package ch.urinet.archiv.ablage.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Ergebnis von {@code sperreVerlaengern} je Datei (Req 9.7): Eine Sperre wird gesetzt oder verlängert, nie verkürzt; ein
 * früheres Datum ergibt {@code KONFLIKT} (Aktion {@code SPERRE_KONFLIKT}) und lässt die Sperre unverändert.
 */
public record SperrAenderung(String schluessel, String versionId, Ergebnis ergebnis, Optional<Instant> sperreBis) {

    public enum Ergebnis {
        GESETZT,
        VERLAENGERT,
        UNVERAENDERT,
        KONFLIKT,
        KEINE_SPERRE
    }
}
