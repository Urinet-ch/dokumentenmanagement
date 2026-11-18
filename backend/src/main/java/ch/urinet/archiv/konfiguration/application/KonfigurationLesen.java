package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;

/** Liefert das aktuelle Konfigurationsabbild des Mandanten im laufenden Kontext. */
public interface KonfigurationLesen {

    Konfiguration aktuell();
}
