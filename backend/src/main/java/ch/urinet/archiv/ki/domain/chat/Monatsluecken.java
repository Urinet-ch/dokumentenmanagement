package ch.urinet.archiv.ki.domain.chat;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Monate ohne Dokument (Spec 05, design.md «Regeltypen» {@code ERWARTET} mit {@code je: MONAT}, ohne {@code ab}; Übergang
 * in Spec 02, Aufgabe 7.1c): vom ersten Monat mit einem Dokument bis zum letzten abgeschlossenen Monat, begrenzt auf die
 * ganzen Monate des Geschäftsjahres. Vor dem ersten Beleg wird nie eine Lücke behauptet.
 */
public final class Monatsluecken {

    private Monatsluecken() {
    }

    /** Die ganzen Monate des Geschäftsjahres {@code jahr} (nach dem Kalenderjahr seines Endes); leer, wenn es keinen gibt. */
    public record Spanne(YearMonth erster, YearMonth letzter) {
    }

    public static Optional<Spanne> monateDes(MonthDay ende, int jahr) {
        LocalDate letzterTag = ende.atYear(jahr);
        LocalDate ersterTag = ende.atYear(jahr - 1).plusDays(1);
        YearMonth erster = ersterTag.getDayOfMonth() == 1 ? YearMonth.from(ersterTag) : YearMonth.from(ersterTag).plusMonths(1);
        YearMonth letzter = letzterTag.equals(letzterTag.withDayOfMonth(letzterTag.lengthOfMonth()))
                ? YearMonth.from(letzterTag) : YearMonth.from(letzterTag).minusMonths(1);
        return letzter.isBefore(erster) ? Optional.empty() : Optional.of(new Spanne(erster, letzter));
    }

    /** {@code erster}: erster Monat mit einem Dokument überhaupt ({@code null} ohne Dokument); {@code fehlend}: aufsteigend. */
    public record Ergebnis(YearMonth erster, List<YearMonth> fehlend) {

        public Ergebnis {
            fehlend = List.copyOf(fehlend);
        }
    }

    /**
     * @param vorhanden           Monate mit mindestens einem Dokument, aus allen Jahren
     * @param spanne              die Monate des verlangten Geschäftsjahres
     * @param letzterAbgeschlossen letzter Monat, der vorbei ist
     */
    public static Ergebnis pruefen(Set<YearMonth> vorhanden, Spanne spanne, YearMonth letzterAbgeschlossen) {
        return pruefen(vorhanden, spanne, letzterAbgeschlossen, null);
    }

    /** Wie oben, aber ab {@code ab} statt ab dem ersten Dokument, wenn {@code ab} gesetzt ist. */
    public static Ergebnis pruefen(Set<YearMonth> vorhanden, Spanne spanne, YearMonth letzterAbgeschlossen,
                                   YearMonth ab) {
        if (vorhanden.isEmpty() && ab == null) {
            return new Ergebnis(null, List.of());
        }
        YearMonth erster = vorhanden.stream().min(YearMonth::compareTo).orElse(null);
        // «ab» legt den Beginn fest, auch wenn schon frühere Dokumente vorliegen (Spec 05, design.md «Regeltypen»)
        YearMonth beginn = ab != null ? ab : erster;
        YearMonth von = beginn.isAfter(spanne.erster()) ? beginn : spanne.erster();
        YearMonth bis = letzterAbgeschlossen.isBefore(spanne.letzter()) ? letzterAbgeschlossen : spanne.letzter();
        List<YearMonth> fehlend = new ArrayList<>();
        for (YearMonth m = von; !m.isAfter(bis); m = m.plusMonths(1)) {
            if (!vorhanden.contains(m)) {
                fehlend.add(m);
            }
        }
        return new Ergebnis(erster, fehlend);
    }
}
