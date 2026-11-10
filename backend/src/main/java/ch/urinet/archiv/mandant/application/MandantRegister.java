package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.List;

/** Driven Port: Mandantenregister in {@code public.mandant}. */
public interface MandantRegister {

    boolean existiert(MandantCode code);

    void eintragen(Mandant mandant);

    List<Mandant> aktive();
}
