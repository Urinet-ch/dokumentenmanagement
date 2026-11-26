package ch.urinet.archiv.wissen.domain;

import ch.urinet.archiv.gemeinsam.domain.Seitentext;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prüft KI-Auszüge gegen den Volltext der angegebenen PDF-Seite, normalisiert wie {@link WissensPruefung}. */
public final class WissenIngestAuszuege {

    private static final Pattern WORT = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern ZAHL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])\\p{N}+(?:[.,:/-]\\p{N}+)*(?![\\p{L}\\p{N}])");
    private static final Set<String> SATZANFAENGE = Set.of(
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer", "eines", "einem", "einen",
            "und", "oder", "aber", "auch", "so", "wenn", "falls", "bei", "mit", "für", "von", "im", "in");
    private static final Set<String> RECHTSFORMEN = Set.of("ag", "gmbh", "sàrl", "sa", "kg", "ltd", "inc");

    private WissenIngestAuszuege() {
    }

    public static boolean belegt(String seitentext, String auszug) {
        if (seitentext == null || auszug == null || auszug.isBlank()
                || auszug.codePointCount(0, auszug.length()) > WissensPruefung.AUSZUG_MAX_ZEICHEN) {
            return false;
        }
        String seite = normalisieren(seitentext);
        String zitat = normalisieren(auszug);
        return !zitat.isBlank() && seite.contains(zitat);
    }

    public static Pruefung pruefen(String aussage, String auszug, Seitentext dokument,
                                   WissenIngestGrenzen grenzen) {
        if (aussage == null || aussage.isBlank() || dokument == null || grenzen == null
                || !auszugZulaessig(auszug, grenzen.auszugMindestZeichen())) {
            return new Pruefung(false, List.of());
        }
        String zitat = normalisieren(auszug);
        Set<String> kopfFusszeilen = wiederkehrendeRandzeilen(dokument);
        List<Integer> fundstellen = new ArrayList<>();
        for (int i = 0; i < dokument.anzahl(); i++) {
            if (seiteEnthaeltAuszug(dokument.seiten().get(i), zitat, kopfFusszeilen)) {
                fundstellen.add(i + 1);
            }
        }
        boolean belegt = !fundstellen.isEmpty()
                && ueberlappung(aussage, zitat, grenzen.aussageUeberlappung());
        return new Pruefung(belegt, fundstellen);
    }

    public static boolean kommtVor(String auszug, Seitentext dokument, int von, int bis) {
        if (auszug == null || auszug.isBlank() || dokument == null || von < 1 || bis < von
                || bis > dokument.anzahl()) {
            return false;
        }
        String zitat = normalisieren(auszug);
        if (zitat.isBlank()) {
            return false;
        }
        Set<String> kopfFusszeilen = wiederkehrendeRandzeilen(dokument);
        for (int seite = von; seite <= bis; seite++) {
            if (seiteEnthaeltAuszug(dokument.seiten().get(seite - 1), zitat, kopfFusszeilen)) {
                return true;
            }
        }
        return false;
    }

    static String normalisieren(String text) {
        String normalisiert = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("(?s)```[^\\n]*\\n?(.*?)```", "$1")
                .replaceAll("\\[([^\\]]+)]\\([^)]+\\)", "$1")
                .replaceAll("`+", "")
                .replaceAll("(?<!\\w)(\\*\\*|__|\\*|_)(.+?)\\1(?!\\w)", "$2")
                .replaceAll("(?i)\\s*\\((?:https?://|www\\.|link(?:text)?\\b|hier\\b|weiterlesen\\b"
                        + "|mehr\\s+(?:dazu|informationen|details|erfahren|lesen)\\b)[^)]*\\)\\s*$", "")
                .replaceAll("(?<=[\\p{L}\\p{N}])[-\\u2010\\u2011\\u00ad]\\s*\\n\\s*(?=[\\p{L}])", "")
                .replaceAll("[‘’‚‛´“”„‟«»\"']", "")
                .replaceAll("(?:->|=>|[→⇒⟶⟹➜➔])", " → ")
                .replaceAll("(?:<-|<=|[←⇐⟵⟸])", " ← ")
                .replaceAll("[↑⇑]", " ↑ ")
                .replaceAll("[↓⇓]", " ↓ ")
                .replaceAll("[↔⇔⟷]", " ↔ ")
                .replace('|', ' ');
        StringBuilder ergebnis = new StringBuilder(normalisiert.length());
        boolean leerraum = true;
        for (int i = 0; i < normalisiert.length(); i++) {
            char zeichen = normalisiert.charAt(i);
            if ((zeichen == '-' || zeichen == '\u00ad') && i + 1 < normalisiert.length()
                    && (normalisiert.charAt(i + 1) == '\n' || normalisiert.charAt(i + 1) == '\r')) {
                i++;
                if (i + 1 < normalisiert.length() && normalisiert.charAt(i) == '\r'
                        && normalisiert.charAt(i + 1) == '\n') {
                    i++;
                }
                continue;
            }
            if (Character.isWhitespace(zeichen)) {
                if (!leerraum) {
                    ergebnis.append(' ');
                }
                leerraum = true;
            } else {
                ergebnis.append(zeichen);
                leerraum = false;
            }
        }
        return ergebnis.toString().strip();
    }

    private static boolean auszugZulaessig(String auszug, int mindestZeichen) {
        if (auszug == null || auszug.isBlank()
                || auszug.codePointCount(0, auszug.length()) > WissensPruefung.AUSZUG_MAX_ZEICHEN) {
            return false;
        }
        String zitat = normalisieren(auszug);
        return !zitat.toLowerCase(Locale.ROOT).contains("seiteninhalt (nicht vertrauenswürdige daten)")
                && zitat.codePointCount(0, zitat.length()) >= mindestZeichen;
    }

    private static boolean seiteEnthaeltAuszug(String seitentext, String zitat) {
        return seiteEnthaeltAuszug(seitentext, zitat, Set.of());
    }

    private static boolean seiteEnthaeltAuszug(String seitentext, String zitat, Set<String> kopfFusszeilen) {
        String inhalt = randzeilenEntfernen(seitentext, kopfFusszeilen);
        return normalisieren(inhalt).contains(zitat);
    }

    private static Set<String> wiederkehrendeRandzeilen(Seitentext dokument) {
        Map<String, Integer> vorkommen = new HashMap<>();
        for (String seite : dokument.seiten()) {
            List<String> zeilen = seite.lines().filter(line -> !normalisieren(line).isBlank()).toList();
            Set<String> randzeilen = new HashSet<>();
            for (int i = 0; i < Math.min(2, zeilen.size()); i++) {
                randzeilen.add(normalisieren(zeilen.get(i)));
                randzeilen.add(normalisieren(zeilen.get(zeilen.size() - i - 1)));
            }
            randzeilen.stream().filter(line -> line.codePointCount(0, line.length()) >= 5)
                    .forEach(line -> vorkommen.merge(line, 1, Integer::sum));
        }
        return vorkommen.entrySet().stream().filter(entry -> entry.getValue() >= 2).map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String randzeilenEntfernen(String seitentext, Set<String> kopfFusszeilen) {
        if (kopfFusszeilen.isEmpty()) {
            return seitentext;
        }
        List<String> zeilen = seitentext.lines().toList();
        int erste = 0;
        while (erste < zeilen.size() && normalisieren(zeilen.get(erste)).isBlank()) {
            erste++;
        }
        int letzte = zeilen.size() - 1;
        while (letzte >= erste && normalisieren(zeilen.get(letzte)).isBlank()) {
            letzte--;
        }
        List<String> behalten = new ArrayList<>(zeilen.size());
        for (int i = 0; i < zeilen.size(); i++) {
            boolean rand = i <= erste + 1 || i >= letzte - 1;
            if (!rand || !kopfFusszeilen.contains(normalisieren(zeilen.get(i)))) {
                behalten.add(zeilen.get(i));
            }
        }
        return String.join("\n", behalten);
    }

    private static boolean ueberlappung(String aussage, String zitat, double minimum) {
        Set<String> aussageWoerter = woerter(aussage);
        Set<String> zitatWoerter = woerter(zitat);
        if (aussageWoerter.isEmpty()) {
            return false;
        }
        long gemeinsameWoerter = aussageWoerter.stream().filter(zitatWoerter::contains).count();
        if ((double) gemeinsameWoerter / aussageWoerter.size() < minimum) {
            return false;
        }
        return zahlen(aussage).stream().allMatch(zahlen(zitat)::contains)
                && eigennamen(aussage).stream().allMatch(zitatWoerter::contains);
    }

    private static Set<String> woerter(String text) {
        Set<String> result = new HashSet<>();
        Matcher matcher = WORT.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String wort = matcher.group();
            if (wort.codePointCount(0, wort.length()) >= 3 && !SATZANFAENGE.contains(wort)
                    && !zahl(wort)) {
                result.add(wort);
            }
        }
        return result;
    }

    private static Set<String> zahlen(String text) {
        Set<String> result = new HashSet<>();
        Matcher matcher = ZAHL.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private static boolean zahl(String wort) {
        return wort.codePoints().allMatch(Character::isDigit);
    }

    private static Set<String> eigennamen(String text) {
        List<String> woerter = new ArrayList<>();
        Matcher matcher = WORT.matcher(text);
        while (matcher.find()) {
            woerter.add(matcher.group());
        }
        Set<String> result = new HashSet<>();
        for (int i = 0; i < woerter.size(); i++) {
            String wort = woerter.get(i);
            String kleingeschrieben = wort.toLowerCase(Locale.ROOT);
            if (RECHTSFORMEN.contains(kleingeschrieben) && i > 0) {
                result.add(woerter.get(i - 1).toLowerCase(Locale.ROOT));
            } else if (wort.length() > 1 && wort.substring(1).matches(".*\\p{Lu}.*")) {
                result.add(kleingeschrieben);
            } else if (wort.length() > 1 && wort.equals(wort.toUpperCase(Locale.ROOT))
                    && wort.matches(".*\\p{L}.*")) {
                result.add(kleingeschrieben);
            } else if (i > 0 && Character.isUpperCase(wort.codePointAt(0))
                    && !SATZANFAENGE.contains(kleingeschrieben)
                    && !SATZANFAENGE.contains(woerter.get(i - 1).toLowerCase(Locale.ROOT))
                    && Character.isUpperCase(woerter.get(i - 1).codePointAt(0))) {
                result.add(kleingeschrieben);
                result.add(woerter.get(i - 1).toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    public record Pruefung(boolean belegt, List<Integer> seiten) {
        public Pruefung {
            seiten = List.copyOf(seiten);
        }
    }
}
