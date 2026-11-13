package ch.urinet.archiv.ki.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Differenz zwischen KI-Vorschlag und bestätigtem Ergebnis (Req 7.7, design.md «KI», Korrektur). Ein Feld zählt als
 * bestätigt, wenn Vorschlag oder Ergebnis einen Wert tragen; als korrigiert, wenn die Werte verschieden sind. Die
 * Finanzfelder zählen nur, wenn die bestätigte Art die Gruppe {@code finanz} hat. {@link #korrigiert()} enthält nur die
 * bestätigten Werte der korrigierten Felder ({@code null}, wenn der Benutzer einen Wert entfernt hat).
 */
public record Korrektur(Set<Feld> bestaetigt, Map<Feld, String> korrigiert) {

    static final Set<Feld> FINANZ = EnumSet.of(Feld.BELEGNUMMER, Feld.RICHTUNG, Feld.BETRAG, Feld.WAEHRUNG,
            Feld.FAELLIGKEIT, Feld.QR_REFERENZ);

    public Korrektur {
        bestaetigt = Collections.unmodifiableSet(bestaetigt.isEmpty() ? EnumSet.noneOf(Feld.class)
                : EnumSet.copyOf(bestaetigt));
        korrigiert = Collections.unmodifiableMap(korrigiert.isEmpty() ? new EnumMap<>(Feld.class)
                : new EnumMap<>(korrigiert));
    }

    /** Mindestens ein Feld korrigiert: Dann entsteht ein Beispiel. */
    public boolean beispiel() {
        return !korrigiert.isEmpty();
    }

    /**
     * @param vorschlag          Werte aus {@code ki_vorschlag} (Schlüssel nach {@link Feld}; fehlende gelten als leer)
     * @param ergebnis           bestätigte Werte, Datum ISO, Betrag als Text
     * @param finanz             die bestätigte Art hat die Gruppe {@code finanz}
     * @param partnerUebernommen der bestätigte Partner ist der Kontakt, den der Abgleich mit dem vorgeschlagenen Namen
     *                           verknüpft hat; dann gilt der Partner unabhängig von der Schreibweise als übernommen
     */
    public static Korrektur ermitteln(Map<Feld, String> vorschlag, Map<Feld, String> ergebnis, boolean finanz,
                                      boolean partnerUebernommen) {
        Set<Feld> bestaetigt = EnumSet.noneOf(Feld.class);
        Map<Feld, String> korrigiert = new EnumMap<>(Feld.class);
        for (Feld feld : Feld.values()) {
            if (!finanz && FINANZ.contains(feld)) {
                continue;
            }
            String v = leer(vorschlag.get(feld));
            String e = leer(ergebnis.get(feld));
            if (v == null && e == null) {
                continue;
            }
            bestaetigt.add(feld);
            boolean gleich = feld == Feld.PARTNER && partnerUebernommen || gleich(feld, v, e);
            if (!gleich) {
                korrigiert.put(feld, e);
            }
        }
        return new Korrektur(bestaetigt, korrigiert);
    }

    private static boolean gleich(Feld feld, String v, String e) {
        if (v == null || e == null) {
            return false;
        }
        return switch (feld) {
            case BETRAG -> betrag(v, e);
            case PARTNER -> name(v).equals(name(e));
            default -> v.equals(e);
        };
    }

    private static boolean betrag(String v, String e) {
        try {
            return new BigDecimal(v).compareTo(new BigDecimal(e)) == 0;
        } catch (NumberFormatException ungueltig) {
            return v.equals(e);
        }
    }

    private static String name(String wert) {
        return wert.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String leer(String wert) {
        if (wert == null) {
            return null;
        }
        String s = wert.strip();
        return s.isEmpty() ? null : s;
    }
}
