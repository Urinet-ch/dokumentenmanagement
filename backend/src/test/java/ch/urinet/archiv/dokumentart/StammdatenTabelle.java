package ch.urinet.archiv.dokumentart;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stammdaten-Tabelle der Dokumentarten in design.md (Req 9.2, P6) und ihr Gegenstück in der Konfigurationsdatei.
 * Felder mit {@code _} am Anfang fallen weg; nicht-tabellarische Felder müssen ausdrücklich ausgenommen sein.
 */
final class StammdatenTabelle {

    static final Path DESIGN = Path.of("../.kiro/specs/01-archiv-kern/design.md");

    static final String START = "<!-- seed:dokumentarten:start -->";
    static final String ENDE = "<!-- seed:dokumentarten:ende -->";
    /** Einzige Stelle des Tabellenkopfs (Review PR #38). */
    static final List<String> KOPF = List.of("Code", "Name", "Gruppen", "Pflichtfelder", "Vertraulichkeit",
            "Jahre", "ab", "Fristfeld", "Pflicht", "Beschreibung", "Selbst ausgestellt", "Belegnummer-Muster",
            "Fremdbeleg-Art", "Ohne Zahlungsdaten", "KI-Vorschlag", "KI-Felder", "Reihenfolge",
            "Klassifikationen");
    private static final Pattern TRENNZEILE = Pattern.compile("^\\|( --- \\|)+$");
    private static final Pattern CODE = Pattern.compile("^`([A-Z_]+)`$");
    private static final String LEER = "–";
    private static final Set<String> ROOT_FELDER = Set.of("format", "einstellungen", "kiTexte", "klassifikationen",
            "arten", "regeln");
    private static final Set<String> SEED_FELDER = Set.of("code", "name", "beschreibung", "gruppen", "pflichtfelder",
            "zusatzSchema", "vertraulichkeit", "aufbewahrung", "gesetzlichePflicht", "aktiv", "selbstAusgestellt",
            "belegnummerMuster", "fremdbelegArt", "ohneZahlungsdatenArt", "kiVorschlag", "kiFelder", "kiHinweise",
            "reihenfolge", "klassifikationen");

    /**
     * Eine Zeile der Tabelle; optionale Werte entsprechen «–».
     */
    record Art(String code, String name, List<String> gruppen, List<String> pflichtfelder, String vertraulichkeit,
               Integer jahre, String ab, String fristfeld, boolean pflicht, boolean aktiv, String beschreibung,
               boolean selbstAusgestellt, List<String> belegnummerMuster, String fremdbelegArt,
               String ohneZahlungsdatenArt, boolean kiVorschlag, List<String> kiFelder, int reihenfolge,
               List<String> klassifikationen) {

        Art {
            gruppen = List.copyOf(gruppen);
            pflichtfelder = List.copyOf(pflichtfelder);
            belegnummerMuster = List.copyOf(belegnummerMuster);
            kiFelder = List.copyOf(kiFelder);
            klassifikationen = List.copyOf(klassifikationen);
        }
    }

    private StammdatenTabelle() {
    }

    /** Kopf- und Trennzeile der Tabelle in Markdown, aus {@link #KOPF}. */
    static List<String> kopfzeilen() {
        return List.of("| " + String.join(" | ", KOPF) + " |", "|" + " --- |".repeat(KOPF.size()));
    }

    static List<Art> ausDesign() throws IOException {
        return tabelle(Files.readAllLines(DESIGN, StandardCharsets.UTF_8));
    }

    /** Zeilen zwischen den Markierungen; «Alle Arten aktiv» (design.md), darum {@code aktiv = true}. */
    static List<Art> tabelle(List<String> zeilen) {
        int start = zeilen.indexOf(START);
        int ende = zeilen.indexOf(ENDE);
        if (start < 0 || ende < start) {
            throw new IllegalArgumentException("Markierungen der Stammdaten-Tabelle fehlen");
        }
        List<String> tabelle = zeilen.subList(start + 1, ende);
        if (tabelle.size() < 2 || !zellen(tabelle.get(0)).equals(KOPF) || !TRENNZEILE.matcher(tabelle.get(1)).matches()) {
            throw new IllegalArgumentException("Kopf der Stammdaten-Tabelle weicht ab");
        }
        List<Art> arten = new ArrayList<>();
        for (String zeile : tabelle.subList(2, tabelle.size())) {
            List<String> z = zellen(zeile);
            if (z.size() != KOPF.size()) {
                throw new IllegalArgumentException("Zeile mit " + z.size() + " statt " + KOPF.size() + " Spalten: " + zeile);
            }
            Matcher code = CODE.matcher(z.get(0));
            if (!code.matches()) {
                throw new IllegalArgumentException("Code nicht im Format `CODE`: " + z.get(0));
            }
            arten.add(new Art(code.group(1), z.get(1), liste(z.get(2)), liste(z.get(3)), z.get(4),
                    z.get(5).equals("unbefristet") ? null : Integer.valueOf(z.get(5)),
                    nullable(z.get(6)), nullable(z.get(7)), janein(z.get(8)), true, nullable(z.get(9)),
                    janein(z.get(10)), liste(z.get(11)), nullable(z.get(12)), nullable(z.get(13)),
                    janein(z.get(14)), liste(z.get(15)), Integer.parseInt(z.get(16)), liste(z.get(17))));
        }
        return arten;
    }

    /** Liest den Seed so streng wie die Konfigurationsdatei: unbekannte Fachfelder bleiben nicht unbemerkt. */
    static List<Art> ausSeed(JsonNode seed) {
        seed.fieldNames().forEachRemaining(feld -> {
            if (!feld.startsWith("_") && !ROOT_FELDER.contains(feld)) {
                throw new IllegalArgumentException("Unbekanntes Feld im Seed: " + feld);
            }
        });
        List<Art> arten = new ArrayList<>();
        for (JsonNode art : seed.path("arten")) {
            art.fieldNames().forEachRemaining(feld -> {
                if (!feld.startsWith("_") && !SEED_FELDER.contains(feld)) {
                    throw new IllegalArgumentException("Feld ohne Spalte in der Stammdaten-Tabelle: " + feld);
                }
            });
            JsonNode aufbewahrung = art.path("aufbewahrung");
            arten.add(new Art(art.path("code").asText(), art.path("name").asText(), texte(art.path("gruppen")),
                    texte(art.path("pflichtfelder")), art.path("vertraulichkeit").asText(), jahre(aufbewahrung),
                    textOderNull(aufbewahrung.path("ab")), textOderNull(aufbewahrung.path("feld")), pflicht(art),
                    art.path("aktiv").asBoolean(true), beschreibung(art),
                    art.path("selbstAusgestellt").asBoolean(false), texte(art.path("belegnummerMuster")),
                    textOderNull(art.path("fremdbelegArt")), textOderNull(art.path("ohneZahlungsdatenArt")),
                    art.path("kiVorschlag").asBoolean(true), texte(art.path("kiFelder")),
                    art.path("reihenfolge").asInt(0), codes(art.path("klassifikationen"))));
        }
        return arten;
    }

    private static Integer jahre(JsonNode aufbewahrung) {
        JsonNode jahre = aufbewahrung.path("jahre");
        if (jahre.isNull()) {
            return null;
        }
        if (!jahre.isIntegralNumber() || !jahre.canConvertToInt()) {
            throw new IllegalArgumentException("aufbewahrung.jahre fehlt oder ist keine Zahl");
        }
        return jahre.intValue();
    }

    private static String beschreibung(JsonNode art) {
        JsonNode beschreibung = art.path("beschreibung");
        if (beschreibung.isMissingNode()) {
            return null;
        }
        if (!beschreibung.isTextual() || beschreibung.asText().isBlank()) {
            throw new IllegalArgumentException("beschreibung ist kein Text oder leer: " + art.path("code"));
        }
        return beschreibung.asText();
    }

    private static boolean pflicht(JsonNode art) {
        if (!art.path("gesetzlichePflicht").isBoolean()) {
            throw new IllegalArgumentException("gesetzlichePflicht fehlt oder ist nicht boolesch: " + art.path("code"));
        }
        return art.path("gesetzlichePflicht").booleanValue();
    }

    private static List<String> codes(JsonNode klassifikationen) {
        List<String> codes = new ArrayList<>();
        klassifikationen.forEach(zuordnung -> codes.add(zuordnung.path("code").asText()));
        return codes;
    }

    private static String textOderNull(JsonNode wert) {
        return wert.isMissingNode() || wert.isNull() ? null : wert.asText();
    }

    private static List<String> zellen(String zeile) {
        String inhalt = zeile.strip();
        if (!inhalt.startsWith("|") || !inhalt.endsWith("|")) {
            throw new IllegalArgumentException("Keine Tabellenzeile: " + zeile);
        }
        return Arrays.stream(inhalt.substring(1, inhalt.length() - 1).split("\\|", -1)).map(String::strip).toList();
    }

    private static List<String> liste(String zelle) {
        return zelle.equals(LEER) ? List.of() : Arrays.stream(zelle.split(",")).map(String::strip).toList();
    }

    private static boolean janein(String zelle) {
        return switch (zelle) {
            case "ja" -> true;
            case "nein" -> false;
            default -> throw new IllegalArgumentException("Pflicht weder «ja» noch «nein»: " + zelle);
        };
    }

    private static String nullable(String zelle) {
        return zelle.equals(LEER) ? null : zelle;
    }

    private static List<String> texte(JsonNode liste) {
        List<String> ergebnis = new ArrayList<>();
        liste.forEach(e -> ergebnis.add(e.asText()));
        return ergebnis;
    }
}
