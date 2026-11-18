package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Regel;

import java.util.List;

public interface RegelRepository {

    List<Regel> alle();

    void ersetzen(List<Regel> regeln);
}
