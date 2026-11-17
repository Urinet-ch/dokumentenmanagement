package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.MandantBericht;
import ch.urinet.archiv.mandant.domain.MandantBerichtFenster;
import ch.urinet.archiv.mandant.domain.MandantCode;

/** Driven Port für die ausschliesslich aggregierten Kennzahlen eines Mandanten. */
public interface MandantBerichtDaten {

    MandantBericht erheben(MandantCode mandant, MandantBerichtFenster fenster);
}
