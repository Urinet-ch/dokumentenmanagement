package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration.DateinamenRegel;

import java.util.List;

public interface DateinamenRegelRepository {

    List<DateinamenRegel> alle();

    void ersetzen(List<DateinamenRegel> regeln);
}
