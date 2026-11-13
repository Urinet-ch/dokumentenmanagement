package ch.urinet.archiv.export.domain;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Objects;

/**
 * Geschäftsjahr eines Mandanten, bezeichnet nach dem Kalenderjahr seines Endes (Spec 01, design.md «Aufbewahrung»):
 * GJ-Ende {@code 06-30}, Jahr 2027 → 01.07.2026 bis 30.06.2027. Ein Ende am 29.02. fällt in Jahren ohne Schalttag auf
 * den 28.02. ({@link MonthDay#atYear}).
 */
public record Geschaeftsjahr(int jahr, MonthDay ende) {

    public static final int MIN = 1900;
    public static final int MAX = 2999;

    public Geschaeftsjahr {
        Objects.requireNonNull(ende, "ende");
        if (jahr < MIN || jahr > MAX) {
            throw new IllegalArgumentException("Geschäftsjahr ausserhalb " + MIN + "–" + MAX);
        }
    }

    public static boolean gueltig(int jahr) {
        return jahr >= MIN && jahr <= MAX;
    }

    public LocalDate letzterTag() {
        return ende.atYear(jahr);
    }

    public LocalDate ersterTag() {
        return ende.atYear(jahr - 1).plusDays(1);
    }
}
