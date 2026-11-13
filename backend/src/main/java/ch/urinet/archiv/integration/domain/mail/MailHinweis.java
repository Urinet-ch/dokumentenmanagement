package ch.urinet.archiv.integration.domain.mail;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Hinweis an die KI zu einer erfassten Mail (Spec 02, Req 1.2): {@code E-Mail; Von: <adresse>; Datum: <TT.MM.JJJJ HH:mm>;
 * Betreff: <betreff>}, wie der Hinweis des Drive-Imports in {@code dokument.hinweis}. NFC, Steuer-, Format- und
 * Trennzeichen werden Leerzeichen, höchstens {@value #MAX_ZEICHEN} Codepoints (ein zu langer Betreff wird hinten mit «…»
 * gekürzt), damit der Hinweis die Prüfung des Uploads besteht.
 */
public final class MailHinweis {

    public static final int MAX_ZEICHEN = 1000;
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Zurich"));

    private MailHinweis() {
    }

    public static String text(Optional<String> absender, Optional<String> betreff, Optional<Instant> datum) {
        StringBuilder kopf = new StringBuilder("E-Mail");
        absender.map(MailHinweis::sauber).filter(s -> !s.isEmpty()).ifPresent(a -> kopf.append("; Von: ").append(a));
        datum.ifPresent(d -> kopf.append("; Datum: ").append(DATUM.format(d)));
        String vorne = kuerzen(kopf.toString(), MAX_ZEICHEN);
        Optional<String> b = betreff.map(MailHinweis::sauber).filter(s -> !s.isEmpty());
        if (b.isEmpty()) {
            return vorne;
        }
        String mitte = "; Betreff: ";
        int platz = MAX_ZEICHEN - vorne.codePointCount(0, vorne.length()) - mitte.length();
        return platz <= 1 ? vorne : vorne + mitte + kuerzen(b.get(), platz);
    }

    /** NFC; Steuer-, Format-, Zeilen- und Absatztrennzeichen werden Leerzeichen, Leerraum zusammengefasst. */
    static String sauber(String roh) {
        String text = Normalizer.normalize(roh, Normalizer.Form.NFC);
        StringBuilder aus = new StringBuilder();
        text.codePoints().forEach(c -> {
            int typ = Character.getType(c);
            boolean ersetzen = typ == Character.CONTROL || typ == Character.FORMAT || typ == Character.SURROGATE
                    || typ == Character.LINE_SEPARATOR || typ == Character.PARAGRAPH_SEPARATOR
                    || typ == Character.PRIVATE_USE || typ == Character.UNASSIGNED;
            aus.appendCodePoint(ersetzen || Character.isWhitespace(c) ? ' ' : c);
        });
        return aus.toString().replaceAll(" {2,}", " ").strip();
    }

    private static String kuerzen(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…";
    }
}
