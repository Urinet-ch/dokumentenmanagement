package ch.urinet.archiv.spike.golden;

import java.util.List;
import java.util.Objects;

/** Req 0.8: Standardlauf nur Abstimmungsteil; Prüfteil nur mit --gate; echte Belege nur als Realitätsprüfung. */
public enum Modus {
    ABSTIMMEN, GATE, REALITAET;

    public static Modus aus(List<String> argumente) {
        boolean gate = argumente.contains("--gate");
        boolean realitaet = argumente.contains("--realitaet");
        if (gate && realitaet) {
            throw new IllegalArgumentException("--gate und --realitaet schliessen sich aus");
        }
        return gate ? GATE : realitaet ? REALITAET : ABSTIMMEN;
    }

    public String verzeichnis() {
        return name().toLowerCase();
    }

    /** Synthetische Belege des Modus; nie zurückgehaltene ohne GATE, nie abstimmende im GATE. */
    public List<Erwartung> waehle(List<Erwartung> synthetisch) {
        String teil = switch (this) {
            case ABSTIMMEN -> Erwartung.Teil.ABSTIMMEN.wert;
            case GATE -> Erwartung.Teil.ZURUECKGEHALTEN.wert;
            case REALITAET -> null;
        };
        if (teil == null) {
            return List.of();
        }
        return synthetisch.stream().filter(e -> !e.echt() && Objects.equals(e.teil(), teil)).toList();
    }
}
