package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Regel;

/** Zählt bestätigte Dokumente, die eine deklarative Regel verletzen würden. */
public interface RegelVerletzungsZaehler {

    long anzahlBetroffeneDokumente(Regel regel);
}
