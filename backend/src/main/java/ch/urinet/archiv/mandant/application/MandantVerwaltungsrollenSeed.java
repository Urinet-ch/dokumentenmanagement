package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.mandant.domain.MandantVerwaltungsrollen;

/** Driven Port: Verwaltungsrollen, die für neu provisionierte Mandanten konfiguriert sind. */
public interface MandantVerwaltungsrollenSeed {

    MandantVerwaltungsrollen fuer(MandantCode code);
}
