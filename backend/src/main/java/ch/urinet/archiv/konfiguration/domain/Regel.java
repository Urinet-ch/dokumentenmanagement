package ch.urinet.archiv.konfiguration.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Eine deklarative Dokumentregel mit den ausschliesslich typgebundenen Parametern der Spec 05. */
public record Regel(String code, String art, String typ, Map<String, Object> parameter, String schwere,
                    String meldung, boolean aktiv) {

    public Regel {
        parameter = parameter == null ? null : unveraenderlich(parameter);
    }

    public Parameter typisierteParameter() {
        Map<String, Object> p = objekt(parameter);
        return switch (typ == null ? "" : typ) {
            case "PFLICHT_WENN" -> pflichtWenn(p);
            case "EINDEUTIG" -> eindeutig(p);
            case "FOLGE" -> folge(p);
            case "ERWARTET" -> erwartet(p);
            default -> throw ungueltig();
        };
    }

    public sealed interface Parameter permits PflichtWenn, Eindeutig, Folge, Erwartet {
        List<String> felder();
    }

    public record PflichtWenn(String wennFeld, Object gleich, List<String> dann) implements Parameter {
        public PflichtWenn {
            dann = List.copyOf(dann);
        }

        @Override
        public List<String> felder() {
            List<String> felder = new ArrayList<>(dann.size() + 1);
            felder.add(wennFeld);
            felder.addAll(dann);
            return List.copyOf(felder);
        }
    }

    public record Eindeutig(List<String> felder, boolean ueberAlleArten) implements Parameter {
        public Eindeutig {
            felder = List.copyOf(felder);
        }
    }

    public record Folge(String feld, boolean muster, Zeitraum zeitraum, String zeitraumFeld,
                        int beginn) implements Parameter {
        @Override
        public List<String> felder() {
            List<String> felder = new ArrayList<>(2);
            if (feld != null) {
                felder.add(feld);
            }
            if (zeitraumFeld != null) {
                felder.add(zeitraumFeld);
            }
            return List.copyOf(felder);
        }
    }

    public record Erwartet(String je, String datum, String ab, String nurMonat) implements Parameter {
        @Override
        public List<String> felder() {
            List<String> felder = new ArrayList<>(2);
            if (datum != null && !"BELEGDATUM".equals(datum)) {
                felder.add(datum);
            }
            if (nurMonat != null) {
                felder.add(nurMonat);
            }
            return List.copyOf(felder);
        }
    }

    public enum Zeitraum {
        GESCHAEFTSJAHR,
        FELD,
        GESAMT
    }

    private static PflichtWenn pflichtWenn(Map<String, Object> p) {
        schluessel(p, Set.of("wenn", "dann"));
        Map<String, Object> wenn = objekt(p.get("wenn"));
        schluessel(wenn, Set.of("feld", "gleich"));
        Object gleich = wenn.get("gleich");
        if (!(gleich instanceof String || gleich instanceof Boolean || gleich instanceof Number)) {
            throw ungueltig();
        }
        return new PflichtWenn(text(wenn.get("feld")), gleich, texte(p.get("dann")));
    }

    private static Eindeutig eindeutig(Map<String, Object> p) {
        schluesselOptional(p, Set.of("felder"), Set.of("ueberAlleArten"));
        Object ueberAlle = p.getOrDefault("ueberAlleArten", false);
        if (!(ueberAlle instanceof Boolean)) {
            throw ungueltig();
        }
        List<String> felder = texte(p.get("felder"));
        if (felder.isEmpty() || Set.copyOf(felder).size() != felder.size()) {
            throw ungueltig();
        }
        return new Eindeutig(felder, (Boolean) ueberAlle);
    }

    private static Folge folge(Map<String, Object> p) {
        schluesselOptional(p, Set.of("zeitraum"), Set.of("feld", "muster", "beginn"));
        boolean hatFeld = p.containsKey("feld");
        boolean hatMuster = p.containsKey("muster");
        if (hatFeld == hatMuster || hatMuster && !Boolean.TRUE.equals(p.get("muster"))) {
            throw ungueltig();
        }
        String feld = hatFeld ? text(p.get("feld")) : null;
        Object beginn = p.getOrDefault("beginn", 1);
        if (!(beginn instanceof Number nummer)) {
            throw ungueltig();
        }
        int beginnWert;
        try {
            beginnWert = new BigDecimal(nummer.toString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw ungueltig();
        }
        if (beginnWert < 1) {
            throw ungueltig();
        }
        Object zeitraum = p.get("zeitraum");
        if ("GESCHAEFTSJAHR".equals(zeitraum)) {
            return new Folge(feld, hatMuster, Zeitraum.GESCHAEFTSJAHR, null, beginnWert);
        }
        if ("GESAMT".equals(zeitraum)) {
            return new Folge(feld, hatMuster, Zeitraum.GESAMT, null, beginnWert);
        }
        Map<String, Object> feldZeitraum = objekt(zeitraum);
        schluessel(feldZeitraum, Set.of("feld"));
        return new Folge(feld, hatMuster, Zeitraum.FELD, text(feldZeitraum.get("feld")), beginnWert);
    }

    private static Erwartet erwartet(Map<String, Object> p) {
        schluesselOptional(p, Set.of("je", "datum"), Set.of("ab", "nurMonat"));
        String je = text(p.get("je"));
        if (!"MONAT".equals(je) && !"JAHR".equals(je)) {
            throw ungueltig();
        }
        String datum = text(p.get("datum"));
        if (!"BELEGDATUM".equals(datum) && !feldGueltig(datum)) {
            throw ungueltig();
        }
        String ab = p.containsKey("ab") ? text(p.get("ab")) : null;
        if (ab != null) {
            try {
                LocalDate.parse(ab);
            } catch (DateTimeParseException e) {
                throw ungueltig();
            }
        }
        String nurMonat = p.containsKey("nurMonat") ? text(p.get("nurMonat")) : null;
        if (nurMonat != null && (!feldGueltig(nurMonat) || !nurMonat.startsWith("zusatz."))) {
            throw ungueltig();
        }
        if ("JAHR".equals(je) && nurMonat != null) {
            throw ungueltig();
        }
        return new Erwartet(je, datum, ab, nurMonat);
    }

    private static Map<String, Object> objekt(Object wert) {
        if (!(wert instanceof Map<?, ?> map)) {
            throw ungueltig();
        }
        Map<String, Object> ergebnis = new LinkedHashMap<>();
        map.forEach((schluessel, inhalt) -> {
            if (!(schluessel instanceof String text)) {
                throw ungueltig();
            }
            ergebnis.put(text, inhalt);
        });
        return ergebnis;
    }

    private static List<String> texte(Object wert) {
        if (!(wert instanceof List<?> liste)) {
            throw ungueltig();
        }
        List<String> ergebnis = new ArrayList<>(liste.size());
        for (Object eintrag : liste) {
            ergebnis.add(text(eintrag));
        }
        return List.copyOf(ergebnis);
    }

    private static String text(Object wert) {
        if (!(wert instanceof String text) || text.isBlank()) {
            throw ungueltig();
        }
        return text;
    }

    private static void schluessel(Map<String, Object> werte, Set<String> erwartet) {
        if (!werte.keySet().equals(erwartet)) {
            throw ungueltig();
        }
    }

    private static void schluesselOptional(Map<String, Object> werte, Set<String> pflicht, Set<String> optional) {
        Set<String> erlaubt = new java.util.HashSet<>(pflicht);
        erlaubt.addAll(optional);
        if (!werte.keySet().containsAll(pflicht) || !erlaubt.containsAll(werte.keySet())) {
            throw ungueltig();
        }
    }

    public static boolean feldGueltig(String wert) {
        return wert != null && (wert.matches("^[a-z][a-z0-9_]{0,39}$")
                || wert.matches("^zusatz\\.[a-z][a-z0-9_]{0,39}$")
                || wert.matches("^klassifikation\\.[a-z][a-z0-9_]{0,39}$"));
    }

    private static Map<String, Object> unveraenderlich(Map<String, Object> werte) {
        Map<String, Object> kopie = new LinkedHashMap<>();
        werte.forEach((schluessel, wert) -> kopie.put(schluessel, einfrieren(wert)));
        return Collections.unmodifiableMap(kopie);
    }

    private static Object einfrieren(Object wert) {
        if (wert instanceof Map<?, ?> map) {
            Map<String, Object> kopie = new LinkedHashMap<>();
            map.forEach((schluessel, inhalt) -> {
                if (!(schluessel instanceof String text)) {
                    throw ungueltig();
                }
                kopie.put(text, einfrieren(inhalt));
            });
            return Collections.unmodifiableMap(kopie);
        }
        if (wert instanceof List<?> liste) {
            return liste.stream().map(Regel::einfrieren).toList();
        }
        if (wert == null || wert instanceof String || wert instanceof Boolean || wert instanceof Number) {
            return wert;
        }
        throw ungueltig();
    }

    private static IllegalArgumentException ungueltig() {
        return new IllegalArgumentException("Regelparameter ungültig");
    }
}
