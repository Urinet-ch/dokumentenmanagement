package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.sicherheit.domain.Rolle;

import java.util.Set;

/** Verwaltbare Rollen des angemeldeten Mandanten. */
public interface MandantenRollen {
    Set<Rolle> lesen();
}
