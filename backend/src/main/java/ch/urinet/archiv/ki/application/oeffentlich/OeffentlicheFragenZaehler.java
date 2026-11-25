package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.mandant.domain.MandantCode;

/** Zählt gespeicherte öffentliche Fragen ohne Quellen eines Mandanten, ohne ihre Inhalte zu lesen. */
public interface OeffentlicheFragenZaehler {

    long anzahlOhneBeleg(MandantCode mandant);
}
