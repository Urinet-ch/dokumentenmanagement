package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;
import ch.urinet.archiv.konfiguration.domain.KonfigurationsVersion;

import java.util.List;
import java.util.Optional;

public interface KonfigurationVersionsRepository {

    void sperren();

    Optional<KonfigurationsVersion> aktuell();

    Optional<KonfigurationsVersion> version(int nummer);

    List<KonfigurationsVersion> alle();

    KonfigurationsVersion anlegen(int nummer, String wer, String grund, KonfigurationQuelle quelle,
                                  Konfiguration inhalt);
}
