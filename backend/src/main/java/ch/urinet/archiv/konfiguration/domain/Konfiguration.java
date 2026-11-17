package ch.urinet.archiv.konfiguration.domain;

import ch.urinet.archiv.dokumentart.domain.Dokumentart;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Unveränderliches Abbild der fachlichen Konfiguration eines Mandanten. */
public record Konfiguration(int version, Einstellungen einstellungen, List<Dokumentart> dokumentarten) {

    public Konfiguration {
        dokumentarten = List.copyOf(dokumentarten);
    }

    /** Liefert alle Prüfungen, die ohne die späteren Konfigurationsattribute möglich sind. */
    public List<Befund> pruefen() {
        List<Befund> befunde = new ArrayList<>();
        if (version < 1) {
            befunde.add(new Befund("version", "VERSION_UNGUELTIG", "Die Konfigurationsversion muss positiv sein."));
        }
        Set<String> codes = new HashSet<>();
        for (Dokumentart art : dokumentarten) {
            if (!codes.add(art.code())) {
                befunde.add(new Befund("dokumentarten." + art.code(), "DOKUMENTART_DOPPELT",
                        "Der Dokumentart-Code kommt mehrfach vor."));
            }
        }
        return List.copyOf(befunde);
    }

    public record Einstellungen(String geschaeftsjahresende, String uid, List<String> ibans) {
        public Einstellungen {
            ibans = List.copyOf(ibans);
        }
    }

    public record Befund(String pfad, String code, String meldung) {
    }
}
