package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

/** Zählt gespeicherte KI-Beispiele eines Mandanten, ohne Beispielinhalte zu lesen. */
public interface KiBeispielZaehler {

    long anzahl(MandantCode mandant);
}
