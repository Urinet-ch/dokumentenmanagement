package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.domain.MandantCode;

/** Driven Port: Konfiguration des Mandanten aus {@code seed/<code>/konfiguration.json}. */
public interface KonfigurationSeed {

    Konfiguration lesen(MandantCode code);
}
