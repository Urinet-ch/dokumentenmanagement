package ch.urinet.archiv.sicherheit.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static ch.urinet.archiv.sicherheit.domain.Ergebnis.ERLAUBT;
import static ch.urinet.archiv.sicherheit.domain.Ergebnis.NICHT_SICHTBAR;
import static ch.urinet.archiv.sicherheit.domain.Ergebnis.NUR_EIGENE;
import static ch.urinet.archiv.sicherheit.domain.Ergebnis.NUR_LESEND;
import static ch.urinet.archiv.sicherheit.domain.Ergebnis.NUR_SELBST_ERFASSTE;
import static ch.urinet.archiv.sicherheit.domain.Ergebnis.VERBOTEN;

/**
 * Rechtematrix Rolle × Aktion als einzige Domain-Regel (Req 2.6, ADR-009). Quelle ist die Tabelle in requirements.md;
 * {@code RechtematrixSpecTest} vergleicht jede Zelle. Eine neue Zeile (A15–A19, Spec 04) braucht eine Konstante in
 * {@link Aktion} und einen Eintrag hier; fehlt eine Zelle, scheitert schon das Laden der Klasse.
 */
public final class Rechtematrix {

    private static final Map<Aktion, Map<Rolle, Ergebnis>> ZEILEN = new EnumMap<>(Aktion.class);

    static {
        // Spalten: inhaber, mitarbeiter, treuhaender, geschaeftsstelle
        zeile(Aktion.A1, ERLAUBT, ERLAUBT, VERBOTEN, ERLAUBT);
        zeile(Aktion.A2, ERLAUBT, ERLAUBT, ERLAUBT, ERLAUBT);
        zeile(Aktion.A3, ERLAUBT, NICHT_SICHTBAR, ERLAUBT, ERLAUBT);
        zeile(Aktion.A4, ERLAUBT, NUR_EIGENE, NUR_LESEND, ERLAUBT);
        zeile(Aktion.A5, ERLAUBT, NUR_SELBST_ERFASSTE, VERBOTEN, ERLAUBT);
        zeile(Aktion.A6, ERLAUBT, VERBOTEN, VERBOTEN, ERLAUBT);
        zeile(Aktion.A7, ERLAUBT, VERBOTEN, VERBOTEN, ERLAUBT);
        zeile(Aktion.A8, ERLAUBT, VERBOTEN, VERBOTEN, ERLAUBT);
        zeile(Aktion.A9, ERLAUBT, VERBOTEN, VERBOTEN, ERLAUBT);
        zeile(Aktion.A10, ERLAUBT, VERBOTEN, VERBOTEN, ERLAUBT);
        // geschaeftsstelle A11–A14: [offen: Rechte der Geschäftsstelle – Entscheid in 03-zweiter-mandant], bis dahin 403
        zeile(Aktion.A11, ERLAUBT, VERBOTEN, VERBOTEN, VERBOTEN);
        zeile(Aktion.A12, ERLAUBT, VERBOTEN, VERBOTEN, VERBOTEN);
        zeile(Aktion.A13, ERLAUBT, VERBOTEN, VERBOTEN, VERBOTEN);
        zeile(Aktion.A14, ERLAUBT, VERBOTEN, VERBOTEN, VERBOTEN);
        if (ZEILEN.size() != Aktion.values().length) {
            throw new IllegalStateException("Rechtematrix unvollständig");
        }
    }

    private Rechtematrix() {
    }

    private static void zeile(Aktion aktion, Ergebnis inhaber, Ergebnis mitarbeiter, Ergebnis treuhaender,
                              Ergebnis geschaeftsstelle) {
        Map<Rolle, Ergebnis> zellen = new EnumMap<>(Rolle.class);
        zellen.put(Rolle.INHABER, inhaber);
        zellen.put(Rolle.MITARBEITER, mitarbeiter);
        zellen.put(Rolle.TREUHAENDER, treuhaender);
        zellen.put(Rolle.GESCHAEFTSSTELLE, geschaeftsstelle);
        ZEILEN.put(aktion, zellen);
    }

    public static Ergebnis ergebnis(Rolle rolle, Aktion aktion) {
        return ZEILEN.get(aktion).get(rolle);
    }

    /**
     * Rollen, für die die Aktion nicht 403 ist; genau diese gehören in {@code @RolesAllowed} von Aktionen ohne
     * Dokumentbezug (dokumentbezogene Aktionen: alle Rollen, siehe {@link Aktion#dokumentbezogen()}).
     */
    public static Set<Rolle> zugelassen(Aktion aktion) {
        return ZEILEN.get(aktion).entrySet().stream().filter(z -> z.getValue().zugaenglich()).map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
