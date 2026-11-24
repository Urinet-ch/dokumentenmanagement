package ch.urinet.archiv.ki.domain.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
                    + "|(?<![\\p{L}\\p{N}])[-+]?\\d(?:[\\d'’., ]*\\d)?\\s*(?:CHF|EUR|USD|GBP|CAD|AUD|JPY|Fr\\.?)(?![\\p{L}])"
                    + "|(?<![\\p{L}\\p{N}])[€$£]\\s*[-+]?\\d(?:[\\d'’., ]*\\d)?");
    private static final Pattern DATUM = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}"
                    + "|\\d{1,2}\\.?\\s+(?:jan(?:uar)?|feb(?:ruar)?|mär(?:z)?|maerz|apr(?:il)?|mai|jun(?:i)?"
                    + "|jul(?:i)?|aug(?:ust)?|sep(?:tember|t)?|okt(?:ober)?|nov(?:ember)?|dez(?:ember)?)\\.?\\s+"
                    + "\\d{4})(?![\\p{L}\\p{N}])");
    private static final Pattern LEERZEICHEN = Pattern.compile("[\\s-]");

    private final List<NameMuster> partnerMuster;

    private record NameMuster(String name, Pattern muster, String platzhalter) {
    }

    public Schwaerzung(List<List<String>> kontaktUndPartnerNamensgruppen) {
        List<List<String>> gruppen = kontaktUndPartnerNamensgruppen == null ? List.of()
                : kontaktUndPartnerNamensgruppen.stream().filter(gruppe -> gruppe != null)
                .map(gruppe -> gruppe.stream().filter(name -> name != null && !name.isBlank())
                        .map(String::strip).distinct()
                        .sorted(Comparator.comparingInt(String::length).reversed()
                                .thenComparing(String::compareToIgnoreCase))
                        .toList())
                .filter(gruppe -> !gruppe.isEmpty())
                .sorted(Comparator.comparingInt(Schwaerzung::laengsterName).reversed()
                        .thenComparing(Schwaerzung::sortierName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        Map<String, String> platzhalter = new LinkedHashMap<>();
        List<NameMuster> muster = new ArrayList<>();
        int naechsterPlatzhalter = 1;
        for (List<String> gruppe : gruppen) {
            String platzhalterDerGruppe = gruppe.stream().map(Schwaerzung::normalisieren)
                    .map(platzhalter::get).filter(Objects::nonNull).findFirst().orElse(null);
            if (platzhalterDerGruppe == null) {
                platzhalterDerGruppe = "[PARTNER-" + naechsterPlatzhalter++ + "]";
            }
            String gruppenPlatzhalter = platzhalterDerGruppe;
            for (String name : gruppe) {
                String platzhalterDesNamens = platzhalter.computeIfAbsent(normalisieren(name),
                        ignored -> gruppenPlatzhalter);
                muster.add(new NameMuster(name, Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(name)
                        + "(?![\\p{L}\\p{N}])"), platzhalterDesNamens));
            }
        }
        partnerMuster = muster.stream()
                .sorted(Comparator.comparingInt((NameMuster name) -> name.name().length()).reversed())
                .toList();
    }

    public String anwenden(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String geschwaerzt = ibanSchwaerzen(text);
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

    private static String ibanSchwaerzen(String text) {
        Matcher matcher = IBAN.matcher(text);
        StringBuilder ergebnis = new StringBuilder();
        int uebernommenBis = 0;
        int sucheAb = 0;
        while (matcher.find(sucheAb)) {
            int ende = matcher.end();
            // Ein Kandidat kann nach der IBAN weitere Wörter enthalten; nur an Wortgrenzen kürzen.
            while (ende >= matcher.start() + 15) {
                if (Character.isLetterOrDigit(text.charAt(ende - 1))
                        && (ende == text.length() || !Character.isLetterOrDigit(text.charAt(ende)))
                        && iban(text.substring(matcher.start(), ende))) {
                    break;
                }
                ende--;
            }
            if (ende >= matcher.start() + 15) {
                ergebnis.append(text, uebernommenBis, matcher.start()).append("[IBAN]");
                uebernommenBis = ende;
                sucheAb = ende;
            } else {
                sucheAb = matcher.start() + 4;
            }
        }
        return ergebnis.append(text, uebernommenBis, text.length()).toString();
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

    private static int laengsterName(List<String> gruppe) {
        return gruppe.stream().mapToInt(String::length).max().orElse(0);
    }

    private static String sortierName(List<String> gruppe) {
        return gruppe.stream().min(String.CASE_INSENSITIVE_ORDER).orElse("");
    }
}
