package ch.urinet.archiv.sicherheit.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Übersicht aller Aktionen und Rollen, direkt aus der Rechtematrix abgeleitet (Spec 06, Requirement 4.12). */
public record RechteUebersicht(List<AktionRechte> aktionen) {

    public RechteUebersicht {
        aktionen = List.copyOf(aktionen);
    }

    public static RechteUebersicht ausRechtematrix() {
        List<AktionRechte> aktionen = Arrays.stream(Aktion.values()).map(aktion -> {
            Map<Rolle, Ergebnis> rechte = new EnumMap<>(Rolle.class);
            for (Rolle rolle : Rolle.values()) {
                rechte.put(rolle, Rechtematrix.ergebnis(rolle, aktion));
            }
            Map<Rolle, List<Fussnote>> fussnoten = new EnumMap<>(Rolle.class);
            for (Rolle rolle : Rolle.values()) {
                fussnoten.put(rolle, Rechtematrix.fussnoten(rolle, aktion));
            }
            return new AktionRechte(aktion, aktion.bezeichnung(), rechte, fussnoten);
        }).toList();
        return new RechteUebersicht(aktionen);
    }

    public record AktionRechte(Aktion aktion, String bezeichnung, Map<Rolle, Ergebnis> rollen,
                         Map<Rolle, List<Fussnote>> fussnoten) {

        public AktionRechte {
            EnumMap<Rolle, Ergebnis> kopie = new EnumMap<>(Rolle.class);
            kopie.putAll(rollen);
            rollen = Collections.unmodifiableMap(kopie);
            EnumMap<Rolle, List<Fussnote>> notenKopie = new EnumMap<>(Rolle.class);
            fussnoten.forEach((rolle, noten) -> notenKopie.put(rolle, List.copyOf(noten)));
            fussnoten = Collections.unmodifiableMap(notenKopie);
        }
    }
}
