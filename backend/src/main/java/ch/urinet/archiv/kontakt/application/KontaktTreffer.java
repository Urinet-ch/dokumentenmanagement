package ch.urinet.archiv.kontakt.application;

import ch.urinet.archiv.kontakt.domain.Kontakt;

import java.util.Objects;

/** Ein Kontakt mit seinem gespeicherten {@code name_normalisiert} (Abgleich des CSV-Imports, Aufgabe 4.2). */
public record KontaktTreffer(Kontakt kontakt, String nameNormalisiert) {

    public KontaktTreffer {
        Objects.requireNonNull(kontakt, "kontakt");
    }
}
