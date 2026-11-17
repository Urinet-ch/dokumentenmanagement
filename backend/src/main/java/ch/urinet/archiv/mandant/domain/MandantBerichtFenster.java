package ch.urinet.archiv.mandant.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/** Kalenderfenster des Vormonats in der Zeitzone des Betriebs. */
public record MandantBerichtFenster(YearMonth monat, Instant von, Instant bis) {

    public static final ZoneId ZEITZONE = ZoneId.of("Europe/Zurich");

    public MandantBerichtFenster {
        if (monat == null || von == null || bis == null
                || !von.equals(monat.atDay(1).atStartOfDay(ZEITZONE).toInstant())
                || !bis.equals(monat.plusMonths(1).atDay(1).atStartOfDay(ZEITZONE).toInstant())) {
            throw new IllegalArgumentException("Ungültiges Monatsfenster");
        }
    }

    public static MandantBerichtFenster vormonat(Instant jetzt) {
        if (jetzt == null) {
            throw new IllegalArgumentException("Zeitpunkt fehlt");
        }
        return fuer(YearMonth.from(jetzt.atZone(ZEITZONE)).minusMonths(1));
    }

    public static MandantBerichtFenster fuer(YearMonth monat) {
        if (monat == null) {
            throw new IllegalArgumentException("Monat fehlt");
        }
        Instant von = monat.atDay(1).atStartOfDay(ZEITZONE).toInstant();
        Instant bis = monat.plusMonths(1).atDay(1).atStartOfDay(ZEITZONE).toInstant();
        return new MandantBerichtFenster(monat, von, bis);
    }

    public LocalDate ersterTag() {
        return monat.atDay(1);
    }
}
