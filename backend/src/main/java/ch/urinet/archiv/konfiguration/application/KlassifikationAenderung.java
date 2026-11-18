package ch.urinet.archiv.konfiguration.application;

import java.util.Set;

public record KlassifikationAenderung(Set<String> gesetzt, String name, String beschreibung, Boolean mehrfach, Boolean kiVorschlag,
                                      Boolean suchfilter, Boolean oeffentlichFilter, Boolean aktiv) {

    public KlassifikationAenderung {
        gesetzt = Set.copyOf(gesetzt);
    }

    public boolean hat(String feld) {
        return gesetzt.contains(feld);
    }
}
