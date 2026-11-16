package ch.urinet.archiv.ki.domain.oeffentlich;

import java.util.regex.Pattern;

/**
 * Was von einer Frage des öffentlichen Chatbots gespeichert wird (Spec 03, Req 6.5: «keine Gesprächsinhalte mit
 * Personenbezug»). Eine Freitextfrage kann nie ganz frei von Personenangaben sein; dieser Schritt entfernt die, die sich
 * zuverlässig erkennen lassen: E-Mail-Adressen, Telefonnummern und lange Zifferfolgen (IBAN, AHV-Nummer, Kartennummer),
 * Web-Adressen und IP-Adressen. Namen erkennt er nicht; darum bleibt die Aufbewahrung auf 90 Tage begrenzt. Die Frage
 * an das Modell bleibt unverändert, nur die gespeicherte Fassung wird bereinigt.
 */
public final class FrageBereinigung {

    public static final String ERSETZT = "[entfernt]";
    /** Gespeicherte Frage höchstens so lang (Codepoints), unabhängig von der Eingabegrenze. */
    public static final int MAX_ZEICHEN = 500;

    private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+'-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");
    /** IBAN: Länderkürzel, zwei Prüfziffern, dann Gruppen (auch mit Leerzeichen). */
    private static final Pattern IBAN = Pattern.compile("(?i)\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]{2,4}){3,8}\\b");
    /** Sieben oder mehr Ziffern, auch durch Leerzeichen, Punkte, Bindestriche oder Schrägstriche getrennt, mit führendem Plus. */
    private static final Pattern ZIFFERN = Pattern.compile("(?<![\\p{L}\\p{N}])\\+?\\d(?:[ .\\-/]?\\d){6,}(?![\\p{L}\\p{N}])");

    private FrageBereinigung() {
    }

    /** Bereinigte Fassung zum Speichern, auf {@value #MAX_ZEICHEN} Zeichen gekürzt. */
    public static String fuerSpeicher(String frage) {
        String text = frage.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").strip();
        text = EMAIL.matcher(text).replaceAll(ERSETZT);
        text = URL.matcher(text).replaceAll(ERSETZT);
        text = IBAN.matcher(text).replaceAll(ERSETZT);
        text = IPV4.matcher(text).replaceAll(ERSETZT);
        text = ZIFFERN.matcher(text).replaceAll(ERSETZT);
        if (text.codePointCount(0, text.length()) > MAX_ZEICHEN) {
            text = text.substring(0, text.offsetByCodePoints(0, MAX_ZEICHEN));
        }
        return text;
    }
}
