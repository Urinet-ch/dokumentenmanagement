package ch.urinet.archiv.protokoll.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Filter der Einsicht ins Zugriffsprotokoll (Req 10.5): Zeitraum {@code [von, bis)}, Kennung, Dokument und Aktion,
 * jeweils optional und kombiniert; Seite ab 0, Grösse 1 bis {@value #MAX_GROESSE}.
 */
public record ProtokollFilter(Optional<Instant> von, Optional<Instant> bis, Optional<String> benutzer,
                              Optional<UUID> dokumentId, Optional<ProtokollAktion> aktion, int seite, int groesse) {

    public static final int MAX_GROESSE = 100;
    public static final int STANDARD_GROESSE = 50;
    public static final int MAX_BENUTZER_LAENGE = 200;

    public ProtokollFilter {
        Objects.requireNonNull(von, "von");
        Objects.requireNonNull(bis, "bis");
        Objects.requireNonNull(benutzer, "benutzer");
        Objects.requireNonNull(dokumentId, "dokumentId");
        Objects.requireNonNull(aktion, "aktion");
        benutzer = benutzer.map(String::strip).filter(b -> !b.isEmpty());
        if (benutzer.filter(b -> b.length() > MAX_BENUTZER_LAENGE).isPresent()) {
            throw new IllegalArgumentException("benutzer zu lang");
        }
        if (von.isPresent() && bis.isPresent() && !von.get().isBefore(bis.get())) {
            throw new IllegalArgumentException("von nicht vor bis");
        }
        if (seite < 0 || groesse < 1 || groesse > MAX_GROESSE) {
            throw new IllegalArgumentException("Seite oder Grösse ungültig");
        }
    }

    public long versatz() {
        return (long) seite * groesse;
    }
}
