package ch.urinet.archiv.sicherheit.domain;

import java.util.Objects;
import java.util.Set;

/**
 * Welche Dokumente ein Benutzer sieht, abgeleitet aus den Zeilen A2–A5 und A7 der Rechtematrix (Req 2.3, 2.21, 2.24).
 * Der {@code VertraulichkeitFilter} übersetzt die Regel in ein Prädikat der Abfrage, damit Listen, Treffer, Zähler und
 * Paging unsichtbare Dokumente nie verraten.
 * <ul>
 *   <li>Unbestätigte Art: wirkt als {@code PERSONAL} (Req 2.21). Rollen mit Recht A7 sehen alle; bei A4 «nur eigene»
 *       gilt Fussnote ²: {@code person_benutzer} ist der Benutzer oder er hat das Dokument erfasst; alle übrigen Rollen
 *       sehen keine (design.md «Sicherheit», Filter-SQL: {@code treuhaender} nur {@code d.bestaetigt is not null}).</li>
 *   <li>Bestätigt: {@code NORMAL} nach A2, {@code INTERN} nach A3, {@code PERSONAL} nach A4; «nur eigene» heisst
 *       {@code person_benutzer} ist der Benutzer (Fussnote ², der Erfasser-Teil gilt nur vor der Bestätigung).</li>
 *   <li>Eingangskorb (A5): «nur selbst erfasste» heisst zusätzlich {@code erstellt_von} ist der Benutzer (Req 2.24).</li>
 * </ul>
 *
 * @param benutzer                  Benutzer-ID (Claim {@code sub})
 * @param unbestaetigt              Umfang bei Dokumenten ohne bestätigte Art
 * @param normal                    sieht bestätigte {@code NORMAL}-Dokumente (A2)
 * @param intern                    sieht bestätigte {@code INTERN}-Dokumente (A3)
 * @param personal                  Umfang bei bestätigten {@code PERSONAL}-Dokumenten (A4)
 * @param eingangskorbNurErfasste   im Eingangskorb nur selbst erfasste Dokumente (A5)
 */
public record Sichtregel(String benutzer, Unbestaetigt unbestaetigt, boolean normal, boolean intern,
                         Personal personal, boolean eingangskorbNurErfasste) {

    private static final Set<Ergebnis> LESEND = Set.of(Ergebnis.ERLAUBT, Ergebnis.NUR_LESEND);

    /** Umfang bei Dokumenten ohne bestätigte Art. */
    public enum Unbestaetigt {
        /** Recht A7 */
        ALLE,
        /** {@code person_benutzer} = Benutzer oder {@code erstellt_von} = Benutzer (A4 «nur eigene», Fussnote ²) */
        EIGENE,
        KEINE
    }

    /** Umfang bei bestätigten {@code PERSONAL}-Dokumenten. */
    public enum Personal {
        ALLE,
        /** nur mit {@code person_benutzer} = Benutzer */
        NUR_EIGENE,
        KEINE
    }

    public Sichtregel {
        Objects.requireNonNull(benutzer, "benutzer");
        Objects.requireNonNull(unbestaetigt, "unbestaetigt");
        Objects.requireNonNull(personal, "personal");
    }

    public static Sichtregel fuer(Benutzer benutzer) {
        Rolle rolle = benutzer.rolle();
        Ergebnis a4 = Rechtematrix.ergebnis(rolle, Aktion.A4);
        Personal personal = LESEND.contains(a4) ? Personal.ALLE
                : a4 == Ergebnis.NUR_EIGENE ? Personal.NUR_EIGENE : Personal.KEINE;
        Unbestaetigt unbestaetigt = Rechtematrix.ergebnis(rolle, Aktion.A7) == Ergebnis.ERLAUBT ? Unbestaetigt.ALLE
                : personal == Personal.NUR_EIGENE ? Unbestaetigt.EIGENE : Unbestaetigt.KEINE;
        return new Sichtregel(benutzer.id(), unbestaetigt,
                LESEND.contains(Rechtematrix.ergebnis(rolle, Aktion.A2)),
                LESEND.contains(Rechtematrix.ergebnis(rolle, Aktion.A3)),
                personal,
                Rechtematrix.ergebnis(rolle, Aktion.A5) == Ergebnis.NUR_SELBST_ERFASSTE);
    }

    /** Sieht der Benutzer jedes Dokument des Mandanten? Dann braucht die Abfrage kein Prädikat. */
    public boolean uneingeschraenkt() {
        return unbestaetigt == Unbestaetigt.ALLE && normal && intern && personal == Personal.ALLE;
    }
}
