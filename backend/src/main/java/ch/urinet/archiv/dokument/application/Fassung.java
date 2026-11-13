package ch.urinet.archiv.dokument.application;

import java.time.Instant;
import java.util.Objects;

/**
 * Eintrag der Versionsliste eines Dokuments aus {@code dokument_version} (Req 10.1): Nummer der gesicherten Fassung,
 * Zeit, Benutzer-ID und Grund der Änderung. Die gesicherten Werte selbst gehören nicht dazu (design.md «Suche und
 * Dokumentansicht (Aufgabe 4.6)»).
 */
public record Fassung(int version, Instant zeit, String geaendertVon, String grund) {

    public Fassung {
        Objects.requireNonNull(zeit, "zeit");
        Objects.requireNonNull(geaendertVon, "geaendertVon");
    }
}
