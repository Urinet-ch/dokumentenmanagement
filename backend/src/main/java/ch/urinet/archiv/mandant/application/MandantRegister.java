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

    /** Zeitpunkt der Anlage; Beginn der Manifest-Kette (Req 15.1). */
    Optional<Instant> erstellt(MandantCode code);
}
