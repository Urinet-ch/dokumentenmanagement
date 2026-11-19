package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration.Einstellungen;

public interface KonfigurationsEinstellungenSpeichern {

    void speichern(Einstellungen einstellungen);
}
