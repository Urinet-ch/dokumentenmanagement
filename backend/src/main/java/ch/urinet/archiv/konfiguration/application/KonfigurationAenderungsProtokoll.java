package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;

import java.util.List;

public interface KonfigurationAenderungsProtokoll {

    void schreiben(int version, KonfigurationQuelle quelle, String grund, List<Konfiguration.Aenderung> aenderungen);
}
