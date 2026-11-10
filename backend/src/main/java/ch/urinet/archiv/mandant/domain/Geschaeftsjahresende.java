package ch.urinet.archiv.mandant.domain;

import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** Geschäftsjahresende eines Mandanten als {@code MM-TT}, Standard {@code 12-31} (Req 9.6). */
public record Geschaeftsjahresende(String wert) {

    private static final Pattern FORMAT = Pattern.compile("^\\d{2}-\\d{2}$");
    private static final DateTimeFormatter MONAT_TAG = DateTimeFormatter.ofPattern("MM-dd");

    public static final Geschaeftsjahresende STANDARD = new Geschaeftsjahresende("12-31");

    public Geschaeftsjahresende {
        if (wert == null || !FORMAT.matcher(wert).matches()) {
            throw new IllegalArgumentException("Geschäftsjahresende nicht im Format MM-TT: " + wert);
        }
        try {
            MonthDay.parse(wert, MONAT_TAG);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Ungültiges Geschäftsjahresende: " + wert, e);
        }
    }

    public MonthDay monatTag() {
        return MonthDay.parse(wert, MONAT_TAG);
    }
}
