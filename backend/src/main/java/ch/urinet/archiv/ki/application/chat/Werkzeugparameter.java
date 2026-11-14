package ch.urinet.archiv.ki.application.chat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.UUID;

/**
 * Prüft Parameter des Sprachmodells mit denselben Grenzen wie die Suche ({@code Suchanfrage}): Ein Verstoss ist ein
 * {@link WerkzeugFehler} mit dem Namen des Parameters, ohne seinen Wert (tech.md, Logging).
 */
final class Werkzeugparameter {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter SCHWEIZ = DateTimeFormatter.ofPattern("dd.MM.uuuu")
            .withResolverStyle(ResolverStyle.STRICT);

    private Werkzeugparameter() {
    }

    /** Text nach Trim, 1 bis {@code max} Zeichen, ohne Steuerzeichen; leer gilt als nicht angegeben. */
    static String text(String name, String wert, int max, boolean pflicht) {
        String text = wert == null ? null : wert.strip();
        if (text == null || text.isEmpty()) {
            if (pflicht) {
                throw WerkzeugFehler.ungueltig(name);
            }
            return null;
        }
        if (text.length() > max || text.chars().anyMatch(Character::isISOControl)) {
            throw WerkzeugFehler.ungueltig(name);
        }
        return text;
    }

    /** Datum {@code JJJJ-MM-TT} oder {@code TT.MM.JJJJ}; leer gilt als nicht angegeben. */
    static LocalDate datum(String name, String wert, boolean pflicht) {
        String text = text(name, wert, 10, pflicht);
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text, text.contains(".") ? SCHWEIZ : ISO);
        } catch (DateTimeParseException e) {
            throw WerkzeugFehler.ungueltig(name);
        }
    }

    /** Ganzzahl von {@code min} bis {@code max}, ohne Angabe {@code standard}. */
    static int zahl(String name, Integer wert, int min, int max, int standard) {
        if (wert == null) {
            return standard;
        }
        if (wert < min || wert > max) {
            throw WerkzeugFehler.ungueltig(name);
        }
        return wert;
    }

    /** Dokument-ID; keine gültige UUID ist {@code null} (gilt wie ein unbekanntes Dokument). */
    static UUID id(String wert) {
        if (wert == null || wert.strip().length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(wert.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
