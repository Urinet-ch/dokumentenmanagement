package ch.urinet.archiv.ki.domain.chat;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Konservative Schwärzung für Chat-Auswertungen (Spec 02, Requirement 15.7).
 * Freie Namen ausserhalb der übergebenen Kontakt- und Partnernamen werden nicht erkannt.
 */
public final class Schwaerzung {

    private static final Pattern E_MAIL = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}");
    private static final Pattern TELEFON = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:\\+41|0041|0)(?:[\\s()./-]*\\d){8,9}(?!\\d)");
    private static final Pattern IBAN = Pattern.compile(
            "(?i)(?<![A-Z0-9])[A-Z]{2}\\d{2}(?:[\\s-]?[A-Z0-9]){11,30}(?![A-Z0-9])");
    private static final Pattern UID = Pattern.compile(
            "(?i)(?<![A-Z0-9])CHE[-\\s]?\\d{3}[.\\s]?\\d{3}[.\\s]?\\d{3}(?:\\s*(?:MWST|TVA|IVA))?(?![A-Z0-9])");
    private static final Pattern AHV = Pattern.compile(
            "(?<!\\d)756(?:[.\\s-]?\\d{4}){2}[.\\s-]?\\d{2}(?!\\d)");
    private static final Pattern GELDBETRAG = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:CHF|EUR|USD|GBP|CAD|AUD|JPY|Fr\\.?)\\s*[-+]?\\d(?:[\\d'’., ]*\\d)?"
                    + "|(?<![\\p{L}\\p{N}])[-+]?\\d(?:[\\d'’., ]*\\d)?\\s*(?:CHF|EUR|USD|GBP|CAD|AUD|JPY|Fr\\.?)"
                    + "|(?<![\\p{L}\\p{N}])[€$£]\\s*[-+]?\\d(?:[\\d'’., ]*\\d)?");
    private static final Pattern DATUM = Pattern.compile(
            "(?<!\\d)(?:\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2})(?!\\d)");
    private static final Pattern LEERZEICHEN = Pattern.compile("[\\s-]");

    private final Map<String, String> partnerPlatzhalter;
    private final List<NameMuster> partnerMuster;

    private record NameMuster(Pattern muster, String platzhalter) {
    }

    public Schwaerzung(List<String> kontaktUndPartnerNamen) {
        Map<String, String> platzhalter = new LinkedHashMap<>();
        List<String> namen = kontaktUndPartnerNamen == null ? List.of()
                : kontaktUndPartnerNamen.stream().filter(name -> name != null && !name.isBlank())
                .map(String::strip).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareToIgnoreCase))
                .toList();
        for (String name : namen) {
            platzhalter.computeIfAbsent(normalisieren(name), ignored -> "[PARTNER-" + (platzhalter.size() + 1) + "]");
        }
        partnerPlatzhalter = Map.copyOf(platzhalter);
        partnerMuster = namen.stream()
                .map(name -> new NameMuster(Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(name)
                                + "(?![\\p{L}\\p{N}])"),
                        partnerPlatzhalter.get(normalisieren(name))))
                .toList();
    }

    public String anwenden(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String geschwaerzt = ersetzen(text, IBAN, Schwaerzung::iban, "[IBAN]");
        geschwaerzt = ersetzen(geschwaerzt, UID, "[UID]");
        geschwaerzt = ersetzen(geschwaerzt, AHV, "[AHV-NUMMER]");
        geschwaerzt = ersetzen(geschwaerzt, E_MAIL, "[E-MAIL]");
        geschwaerzt = ersetzen(geschwaerzt, TELEFON, "[TELEFON]");
        geschwaerzt = ersetzen(geschwaerzt, GELDBETRAG, "[BETRAG]");
        geschwaerzt = ersetzen(geschwaerzt, DATUM, "[DATUM]");
        for (NameMuster name : partnerMuster) {
            geschwaerzt = name.muster().matcher(geschwaerzt)
                    .replaceAll(Matcher.quoteReplacement(name.platzhalter()));
        }
        return geschwaerzt;
    }

    private static String ersetzen(String text, Pattern muster, String platzhalter) {
        return muster.matcher(text).replaceAll(Matcher.quoteReplacement(platzhalter));
    }

    private static String ersetzen(String text, Pattern muster, java.util.function.Predicate<String> pruefen,
                                   String platzhalter) {
        Matcher matcher = muster.matcher(text);
        StringBuffer ergebnis = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(ergebnis, Matcher.quoteReplacement(
                    pruefen.test(matcher.group()) ? platzhalter : matcher.group()));
        }
        matcher.appendTail(ergebnis);
        return ergebnis.toString();
    }

    private static boolean iban(String wert) {
        String normalisiert = LEERZEICHEN.matcher(wert).replaceAll("").toUpperCase(Locale.ROOT);
        if (normalisiert.length() < 15 || normalisiert.length() > 34) {
            return false;
        }
        String verschoben = normalisiert.substring(4) + normalisiert.substring(0, 4);
        int rest = 0;
        for (int i = 0; i < verschoben.length(); i++) {
            int zahl = Character.digit(verschoben.charAt(i), 36);
            if (zahl < 0) {
                return false;
            }
            String ziffern = Integer.toString(zahl);
            for (int j = 0; j < ziffern.length(); j++) {
                rest = (rest * 10 + ziffern.charAt(j) - '0') % 97;
            }
        }
        return rest == 1;
    }

    private static String normalisieren(String name) {
        return name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
