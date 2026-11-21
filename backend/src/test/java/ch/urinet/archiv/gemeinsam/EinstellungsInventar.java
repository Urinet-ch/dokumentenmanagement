package ch.urinet.archiv.gemeinsam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gleicht die Schlüssel der Anwendung mit den ausdrücklich aufgeführten Inventarpräfixen ab. */
public final class EinstellungsInventar {
    private static final Path DESIGN = Path.of("../.kiro/specs/06-verwaltung-einstellungen-benutzer/design.md");
    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final Pattern PRAEFIX = Pattern.compile("`(archiv\\.[^`]+)`");

    private EinstellungsInventar() {
    }

    public static List<String> luecken() throws IOException {
        List<Eintrag> eintraege = inventar(Files.readAllLines(DESIGN));
        List<String> luecken = new ArrayList<>();
        Properties properties = new Properties();
        try (var leser = Files.newBufferedReader(PROPERTIES)) {
            properties.load(leser);
        }
        for (String name : properties.stringPropertyNames().stream().sorted().toList()) {
            String schluessel = schluessel(name);
            if (schluessel != null && klasse(schluessel, eintraege) == null) {
                luecken.add(schluessel);
            }
        }
        return luecken;
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
