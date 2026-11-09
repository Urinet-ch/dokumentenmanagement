package ch.urinet.archiv.spike.golden;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dokumentarten aus der Tabelle in design.md (zwischen den Seed-Markierungen), damit der Prompt der Spec folgt. */
public final class Dokumentarten {

    public record Art(String code, String name, String gruppen, String pflichtfelder, String vertraulichkeit) {
    }

    static final String START = "<!-- seed:dokumentarten:start -->";
    static final String ENDE = "<!-- seed:dokumentarten:ende -->";
    private static final Pattern ZEILE = Pattern.compile("^\\|\\s*`([A-Z_]+)`\\s*\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|");

    private Dokumentarten() {
    }

    public static List<Art> aus(Path designMd) throws IOException {
        String text = Files.readString(designMd, StandardCharsets.UTF_8);
        int start = text.indexOf(START), ende = text.indexOf(ENDE);
        if (start < 0 || ende < start) {
            throw new IllegalStateException("Tabelle der Dokumentarten nicht gefunden: " + designMd);
        }
        List<Art> arten = new ArrayList<>();
        for (String zeile : text.substring(start, ende).split("\\R")) {
            Matcher m = ZEILE.matcher(zeile);
            if (m.find()) {
                arten.add(new Art(m.group(1), m.group(2).strip(), m.group(3).strip(), m.group(4).strip(),
                        m.group(5).strip().toUpperCase(Locale.ROOT)));
            }
        }
        return arten;
    }

    /** design.md liegt im Repository; gesucht wird vom Arbeitsverzeichnis aufwärts. */
    public static Path designMd(Path start) {
        for (Path p = start.toAbsolutePath(); p != null; p = p.getParent()) {
            Path kandidat = p.resolve(".kiro/specs/01-archiv-kern/design.md");
            if (Files.isRegularFile(kandidat)) {
                return kandidat;
            }
        }
        throw new IllegalStateException("design.md nicht gefunden (GoldenLauf im Repository starten)");
    }

    /**
     * Anzeigetexte nur im Prompt (v5); die Tabelle in design.md bleibt. %s ist der Mandantenname, eingesetzt in Java,
     * weil die Liste selbst der Wert von {dokumentarten} ist und nicht nochmals ersetzt würde.
     */
    static final Map<String, String> ANZEIGE = Map.of(
            "LIEFERANTENBELEG", "Lieferantenbeleg – Rechnung, Facture, Fattura oder Gutschrift eines Lieferanten an %s (Eingangsrechnung)",
            "QUITTUNG", "Quittung / Spesen – Kassenbon, Zahlungsbestätigung, Billett, Parkticket; bereits bezahlt",
            "AUSGANGSRECHNUNG", "Ausgangsrechnung – nur wenn %s im Briefkopf als Absender steht");

    /**
     * eigeneArten: design.md «Mandant», Einstellung eigene_arten (urinet: AUSGANGSRECHNUNG, OFFERTE, LOHNABRECHNUNG).
     * Fremde Arten zuerst, eigene zuletzt (je in Tabellenreihenfolge): Bei Unsicherheit fällt das Modell auf frühe Werte zurück.
     */
    public static String alsPromptListe(List<Art> arten, List<String> eigeneArten, String mandant) {
        List<Art> sortiert = new ArrayList<>(arten.stream().filter(a -> !eigeneArten.contains(a.code())).toList());
        sortiert.addAll(arten.stream().filter(a -> eigeneArten.contains(a.code())).toList());
        StringBuilder sb = new StringBuilder();
        for (Art a : sortiert) {
            String name = ANZEIGE.containsKey(a.code()) ? ANZEIGE.get(a.code()).replace("%s", mandant) : a.name();
            sb.append("- ").append(a.code()).append(": ").append(name).append(", ").append(a.vertraulichkeit());
            if (eigeneArten.contains(a.code())) {
                sb.append(" (stellt der Mandant selbst aus)");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
