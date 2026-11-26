package ch.urinet.archiv.gemeinsam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gleicht Anwendungsschlüssel mit dem ausdrücklich aufgeführten Einstellungsinventar ab. */
public final class EinstellungsInventar {
    private static final Path DESIGN = Path.of("../.kiro/specs/06-verwaltung-einstellungen-benutzer/design.md");
    private static final Path SPECS = Path.of("../.kiro/specs");
    private static final Path DOKUMENTATION = Path.of("../docs");
    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final Path JAVA = Path.of("src/main/java");
    private static final Pattern PRAEFIX = Pattern.compile("`(archiv\\.[^`]+)`");
    private static final Pattern CONFIG_PROPERTY = Pattern.compile(
            "@ConfigProperty\\s*\\(\\s*name\\s*=\\s*\"(archiv\\.[A-Za-z0-9._-]+)\"");
    private static final Pattern JAVA_KONFIGURATIONSSCHLUESSEL =
            Pattern.compile("\"(archiv\\.[A-Za-z0-9._%{}-]+)\"");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern DOKUMENTIERTER_SCHLUESSEL =
            Pattern.compile("archiv\\.[A-Za-z0-9._-]+(?:\\.[A-Za-z0-9._-]+)*");
    private static final Map<String, String> AUSNAHMEN = Map.of(
            "archiv.apps.urinet.ch", "Öffentlicher Hostname, kein Konfigurationsschlüssel.",
            "archiv.dump", "Dateiname eines Datenbank-Dumps.",
            "archiv.einfuehrung", "Schlüssel im Browser-localStorage, keine Backend-Einstellung.",
            "archiv.example.invalid", "Platzhalter-Hostname in Beispielen der Webseiten-Schnittstelle.",
            "archiv.ki.token-datei", "Einstellung der separaten Mini-App im Infomaniak-Spike, nicht des Backends.",
            "archiv.php", "Dateiname des WordPress-Plugins.",
            "archiv.tsa.url", "Historischer, durch archiv.tsa.dienste ersetzter Konfigurationsschlüssel.");

    private EinstellungsInventar() {
    }

    public static List<String> luecken() throws IOException {
        List<Eintrag> eintraege = inventar(Files.readAllLines(DESIGN));
        List<String> luecken = new ArrayList<>();
        for (String schluessel : konfigurationsschluessel()) {
            if (klasse(schluessel, eintraege) == null) {
                luecken.add(schluessel);
            }
        }
        return List.copyOf(luecken);
    }

    static List<String> veralteteInventareintraege() throws IOException {
        List<Eintrag> eintraege = inventar(Files.readAllLines(DESIGN));
        List<String> schluessel = konfigurationsschluessel();
        return eintraege.stream()
                .filter(eintrag -> schluessel.stream().noneMatch(
                        name -> eintrag.muster().matcher(name).matches()))
                .map(Eintrag::praefix)
                .distinct()
                .sorted()
                .toList();
    }

    static List<String> undokumentierteReferenzen() throws IOException {
        Set<String> bekannteSchluessel = Set.copyOf(dokumentierteCodeSchluessel());
        return dokumentierteReferenzen().stream()
                .filter(name -> !istBekannt(name, bekannteSchluessel))
                .filter(name -> !AUSNAHMEN.containsKey(name))
                .sorted()
                .toList();
    }

    static List<String> verwendeteAusnahmen() throws IOException {
        return dokumentierteReferenzen().stream()
                .filter(AUSNAHMEN::containsKey)
                .sorted()
                .toList();
    }

    private static Set<String> dokumentierteReferenzen() throws IOException {
        TreeSet<String> referenzen = new TreeSet<>();
        for (Path datei : dokumentationsdateien()) {
            Matcher inline = INLINE_CODE.matcher(Files.readString(datei));
            while (inline.find()) {
                Matcher schluessel = DOKUMENTIERTER_SCHLUESSEL.matcher(inline.group(1));
                while (schluessel.find()) {
                    referenzen.add(schluessel.group());
                }
            }
        }
        return Set.copyOf(referenzen);
    }

    private static List<Path> dokumentationsdateien() throws IOException {
        TreeSet<Path> dateien = new TreeSet<>();
        try (var dokumente = Files.walk(DOKUMENTATION)) {
            dateien.addAll(dokumente.filter(path -> path.toString().endsWith(".md")).toList());
        }
        try (var specs = Files.list(SPECS)) {
            for (Path spec : specs.toList()) {
                Path design = spec.resolve("design.md");
                if (Files.isRegularFile(design)) {
                    dateien.add(design);
                }
            }
        }
        return List.copyOf(dateien);
    }

    private static boolean istBekannt(String referenz, Set<String> schluessel) {
        String praefix = referenz.endsWith(".") ? referenz : referenz + ".";
        return schluessel.stream().anyMatch(schluesselname ->
                schluesselname.equals(referenz)
                        || schluesselname.startsWith(praefix)
                        || schluesselname.contains("%") && schluesselname.startsWith(referenz));
    }

    private static List<String> konfigurationsschluessel() throws IOException {
        TreeSet<String> schluessel = new TreeSet<>();
        Properties properties = new Properties();
        try (var leser = Files.newBufferedReader(PROPERTIES)) {
            properties.load(leser);
        }
        for (String name : properties.stringPropertyNames().stream().sorted().toList()) {
            String kanonisch = schluessel(name);
            if (kanonisch != null) {
                schluessel.add(kanonisch);
            }
        }
        try (var dateien = Files.walk(JAVA)) {
            for (Path datei : dateien.filter(path -> path.toString().endsWith(".java")).toList()) {
                String inhalt = Files.readString(datei);
                Matcher annotation = CONFIG_PROPERTY.matcher(inhalt);
                while (annotation.find()) {
                    schluessel.add(annotation.group(1));
                }
            }
        }
        return List.copyOf(schluessel);
    }

    private static List<String> dokumentierteCodeSchluessel() throws IOException {
        TreeSet<String> schluessel = new TreeSet<>(konfigurationsschluessel());
        try (var dateien = Files.walk(JAVA)) {
            for (Path datei : dateien.filter(path -> path.toString().endsWith(".java")).toList()) {
                String inhalt = Files.readString(datei);
                Matcher dynamisch = JAVA_KONFIGURATIONSSCHLUESSEL.matcher(inhalt);
                while (dynamisch.find()) {
                    schluessel.add(dynamisch.group(1));
                }
            }
        }
        return List.copyOf(schluessel);
    }

    static String schluessel(String name) {
        if (name.startsWith("%")) {
            int punkt = name.indexOf('.');
            if (punkt < 0 || name.substring(1, punkt).equals("test")) {
                return null;
            }
            name = name.substring(punkt + 1);
        }
        return name.startsWith("archiv.") ? name : null;
    }

    static List<Eintrag> inventar(List<String> zeilen) {
        List<Eintrag> ergebnis = new ArrayList<>();
        char klasse = 0;
        for (String zeile : zeilen) {
            if (zeile.startsWith("### Klasse (a)")) {
                klasse = 'a';
            } else if (zeile.startsWith("### Klasse (b)")) {
                klasse = 'b';
            } else if (zeile.startsWith("### Klasse (c)")) {
                break;
            } else if (klasse != 0 && zeile.startsWith("| ")) {
                String[] spalten = zeile.split("\\|", -1);
                if (spalten.length < 4) {
                    continue;
                }
                Matcher matcher = PRAEFIX.matcher(spalten[klasse == 'a' ? 2 : 1]);
                while (matcher.find()) {
                    String praefix = matcher.group(1);
                    String regex = Pattern.quote(praefix).replace("<code>", "\\E[^.]+\\Q")
                            .replace("*", "\\E.*\\Q");
                    ergebnis.add(new Eintrag(klasse, praefix, Pattern.compile("^" + regex + "$")));
                }
            }
        }
        if (ergebnis.isEmpty()) {
            throw new IllegalStateException("Das Einstellungsinventar enthält keine Präfixe.");
        }
        return List.copyOf(ergebnis);
    }

    static Character klasse(String schluessel, List<Eintrag> eintraege) {
        int laenge = -1;
        Character gefunden = null;
        for (Eintrag eintrag : eintraege) {
            if (!eintrag.muster().matcher(schluessel).matches()) {
                continue;
            }
            int spezifitaet = eintrag.praefix().replace("<code>", "").replace("*", "").length();
            if (spezifitaet > laenge) {
                laenge = spezifitaet;
                gefunden = eintrag.klasse();
            } else if (spezifitaet == laenge && gefunden != eintrag.klasse()) {
                throw new IllegalStateException("Mehrdeutige Inventarklasse für " + schluessel);
            }
        }
        return gefunden;
    }

    record Eintrag(char klasse, String praefix, Pattern muster) {
    }
}
