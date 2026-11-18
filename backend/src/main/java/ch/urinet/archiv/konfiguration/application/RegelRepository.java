package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Regel;

import java.util.List;
import java.util.Optional;

public interface RegelRepository {

    List<Regel> alle();

    Optional<Regel> finden(String code);

    void anlegen(Regel regel);

    void speichern(Regel regel);
}
