package ch.urinet.archiv.konfiguration.domain;

import java.util.List;

public record Klassifikation(String code, String name, String beschreibung, List<KlassifikationWert> werte,
                             boolean mehrfach, boolean kiVorschlag, boolean suchfilter,
                             boolean oeffentlichFilter, boolean aktiv) {

    public Klassifikation {
        werte = werte == null ? List.of() : List.copyOf(werte);
    }
}
