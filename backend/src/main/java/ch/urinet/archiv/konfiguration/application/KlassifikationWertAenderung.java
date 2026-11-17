package ch.urinet.archiv.konfiguration.application;

import java.util.Set;

public record KlassifikationWertAenderung(Set<String> gesetzt, String name, String beschreibung,
                                          Integer reihenfolge, Boolean aktiv) {

    public KlassifikationWertAenderung {
        gesetzt = Set.copyOf(gesetzt);
    }

    public boolean hat(String feld) {
        return gesetzt.contains(feld);
    }
}
