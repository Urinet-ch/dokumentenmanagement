package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration.KiTexte;

public interface KiTexteRepository {

    KiTexte lesen();

    void speichern(KiTexte kiTexte);
}
