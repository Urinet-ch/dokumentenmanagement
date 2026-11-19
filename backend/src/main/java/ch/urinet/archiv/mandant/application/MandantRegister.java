package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Driven Port: Mandantenregister in {@code public.mandant}. */
public interface MandantRegister {

    boolean existiert(MandantCode code);

    void eintragen(Mandant mandant);

    List<Mandant> aktive();

    /**
     * Mandant, der eingetragen, aber noch nie aktiviert wurde (inaktiv, ohne Abbau); leer sonst. Nur so ein Mandant
     * darf die Provisionierung fortsetzen (Req 1.5). Standard leer (fail closed: kein Fortsetzen).
     */
    default Optional<Mandant> inProvisionierung(MandantCode code) {
        return Optional.empty();
    }

    /** Aktiviert einen Mandanten in Provisionierung; jeder andere Zustand bricht ab. */
    default void aktivieren(MandantCode code) {
        throw new IllegalStateException("Mandant nicht in Provisionierung: " + code.wert());
    }

    /** Zeitpunkt der Anlage; Beginn der Manifest-Kette (Req 15.1). */
    Optional<Instant> erstellt(MandantCode code);

}
