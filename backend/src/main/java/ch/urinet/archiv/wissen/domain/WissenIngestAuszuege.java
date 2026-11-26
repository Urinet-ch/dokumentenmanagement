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
            "(?<![\\p{L}\\p{N}])\\p{N}+(?:[\\s\\u00a0\\u202f]\\p{N}{3})*(?:[.,:/-]\\p{N}+)*"
                    + "(?![\\p{L}\\p{N}])");
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
        if (dokument == null || grenzen == null) {
            return new Pruefung(false, List.of());
        }
        return pruefer(dokument, grenzen.randzeilenMindestAnteil()).pruefen(aussage, auszug, grenzen);
    }

    public static Pruefer pruefer(Seitentext dokument, double randzeilenMindestAnteil) {
        if (dokument == null || !Double.isFinite(randzeilenMindestAnteil)
                || randzeilenMindestAnteil <= 0 || randzeilenMindestAnteil > 1) {
            throw new IllegalArgumentException("Ungültige Seiten oder Randzeilen-Schwelle");
        }
        return new Pruefer(dokument, randzeilenMindestAnteil);
    }

    public static boolean kommtVor(String auszug, Seitentext dokument, int von, int bis) {
        if (auszug == null || auszug.isBlank() || dokument == null || von < 1 || bis < von
                || bis > dokument.anzahl()) {
            return false;
        }
        return pruefer(dokument, 0.3).kommtVor(auszug, von, bis);
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
        return zahlenOhneTausendertrenner(ergebnis.toString().strip());
    }

    private static String zahlenOhneTausendertrenner(String text) {
        Matcher matcher = ZAHL.matcher(text);
        StringBuilder normalisiert = new StringBuilder(text.length());
        while (matcher.find()) {
            String zahl = matcher.group().replaceAll("[\\s\\u00a0\\u202f]", "");
            matcher.appendReplacement(normalisiert, Matcher.quoteReplacement(zahl));
        }
        matcher.appendTail(normalisiert);
        return normalisiert.toString();
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

    private static Set<String> wiederkehrendeRandzeilen(List<List<String>> zeilenJeSeite,
                                                         double mindestAnteil) {
        Map<String, Integer> vorkommen = new HashMap<>();
        for (List<String> zeilen : zeilenJeSeite) {
            Set<String> randzeilen = new HashSet<>();
            int erste = ersterInhalt(zeilen);
            int letzte = letzterInhalt(zeilen);
            if (erste >= 0) {
                for (int i = erste; i <= Math.min(letzte, erste + 1); i++) {
                    randzeilen.add(zeilen.get(i));
                }
                for (int i = Math.max(erste, letzte - 1); i <= letzte; i++) {
                    randzeilen.add(zeilen.get(i));
                }
            }
            randzeilen.stream().filter(line -> line.codePointCount(0, line.length()) >= 5)
                    .forEach(line -> vorkommen.merge(line, 1, Integer::sum));
        }
        int schwelle = Math.max(3, (int) Math.ceil(zeilenJeSeite.size() * mindestAnteil));
        return vorkommen.entrySet().stream().filter(entry -> entry.getValue() >= schwelle).map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static int ersterInhalt(List<String> zeilen) {
        for (int i = 0; i < zeilen.size(); i++) {
            if (!zeilen.get(i).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private static int letzterInhalt(List<String> zeilen) {
        for (int i = zeilen.size() - 1; i >= 0; i--) {
            if (!zeilen.get(i).isBlank()) {
                return i;
            }
        }
        return -1;
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
        return zahlen(normalisieren(aussage)).stream().allMatch(zahlen(zitat)::contains)
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
            result.add(matcher.group().replaceAll("[\\s\\u00a0\\u202f]", ""));
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

    public static final class Pruefer {
        private final List<String> seiten;

        private Pruefer(Seitentext dokument, double randzeilenMindestAnteil) {
            List<List<String>> roheZeilen = dokument.seiten().stream()
                    .map(seite -> seite.lines().toList()).toList();
            List<List<String>> normalisierteZeilen = roheZeilen.stream()
                    .map(zeilen -> zeilen.stream().map(WissenIngestAuszuege::normalisieren).toList()).toList();
            Set<String> randzeilen = wiederkehrendeRandzeilen(normalisierteZeilen, randzeilenMindestAnteil);
            this.seiten = java.util.stream.IntStream.range(0, roheZeilen.size())
                    .mapToObj(i -> normalisieren(seiteOhneRandzeilen(
                            roheZeilen.get(i), normalisierteZeilen.get(i), randzeilen)))
                    .toList();
        }

        public Pruefung pruefen(String aussage, String auszug, WissenIngestGrenzen grenzen) {
            if (aussage == null || aussage.isBlank() || grenzen == null
                    || !auszugZulaessig(auszug, grenzen.auszugMindestZeichen())) {
                return new Pruefung(false, List.of());
            }
            String zitat = normalisieren(auszug);
            List<Integer> fundstellen = new ArrayList<>();
            for (int i = 0; i < seiten.size(); i++) {
                if (!zitat.isBlank() && seiten.get(i).contains(zitat)) {
                    fundstellen.add(i + 1);
                }
            }
            boolean belegt = !fundstellen.isEmpty()
                    && ueberlappung(aussage, zitat, grenzen.aussageUeberlappung());
            return new Pruefung(belegt, fundstellen);
        }

        public boolean kommtVor(String auszug, int von, int bis) {
            if (auszug == null || auszug.isBlank() || von < 1 || bis < von || bis > seiten.size()) {
                return false;
            }
            String zitat = normalisieren(auszug);
            if (zitat.isBlank()) {
                return false;
            }
            for (int seite = von; seite <= bis; seite++) {
                if (seiten.get(seite - 1).contains(zitat)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String seiteOhneRandzeilen(List<String> roheZeilen, List<String> normalisierteZeilen,
                                              Set<String> randzeilen) {
        int erste = ersterInhalt(normalisierteZeilen);
        int letzte = letzterInhalt(normalisierteZeilen);
        List<String> behalten = new ArrayList<>(roheZeilen.size());
        for (int i = 0; i < roheZeilen.size(); i++) {
            boolean rand = erste >= 0 && (i <= erste + 1 || i >= letzte - 1);
            if (!rand || !randzeilen.contains(normalisierteZeilen.get(i))) {
                behalten.add(roheZeilen.get(i));
            }
        }
        return String.join("\n", behalten);
    }
}
