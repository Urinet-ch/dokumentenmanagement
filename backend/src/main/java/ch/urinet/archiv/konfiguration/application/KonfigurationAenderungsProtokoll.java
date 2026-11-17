package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;

public interface KonfigurationAenderungsProtokoll {

    void schreiben(int version, KonfigurationQuelle quelle, String grund);
}
