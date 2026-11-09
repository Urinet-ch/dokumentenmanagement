package ch.urinet.archiv.gemeinsam;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Req 16.14 für die CI: Jedes Kriterium, das eine abgehakte Aufgabe ab Welle 1 unter {@code _Anforderungen_} nennt,
 * hat ein Szenario mit Tag {@code @req-x.y} oder einen Nachweis-Vermerk («manuell», «Dokument», «JUnit»), jeweils
 * innerhalb derselben Spec: Jede Feature-Datei trägt auf Feature-Ebene genau einen Tag {@code @spec-NN}.
 * Welle 0 bleibt aussen vor; ihre Kriterien weist der Gate-Bericht nach. Optionale Aufgaben ({@code - [x]* O.n}) zählen nicht.
 */
public final class ReqAbdeckung {

    private static final Pattern AUFGABE = Pattern.compile("^\\s*- \\[([ xX])](\\*?) (\\S+)");
    private static final Pattern WELLE = Pattern.compile("^(\\d+)\\.\\d+$");
    private static final Pattern OPTIONAL = Pattern.compile("^O\\.\\d+$");
    private static final Pattern ANFORDERUNGEN = Pattern.compile("^\\s*(?:- )?_Anforderungen: ([^_]*)_\\s*$");
    private static final Pattern KRITERIEN = Pattern.compile("^(\\d+)\\.(\\d+)(\\s*–\\s*(?:(\\d+)\\.)?(\\d+))?$");
    private static final Pattern SPEC_TAG = Pattern.compile("@spec-(\\d{2})\\b");
    private static final Pattern FEATURE = Pattern.compile("^\\s*(Funktionalität|Funktion|Feature):");
    private static final Pattern REQUIREMENT = Pattern.compile("^#{2,4} Requirement (\\d+)\\b");
    private static final Pattern KRITERIUM = Pattern.compile("^(\\d+)\\. ");
    private static final Pattern NACHWEIS = Pattern.compile("Nachweis: (manuell|Dokument|JUnit)");
    private static final Pattern TAG = Pattern.compile("@req-(\\d+\\.\\d+)\\b");

    private ReqAbdeckung() {
    }

    /** Fehlende Abdeckung als «spec: x.y», sortiert; leer, wenn alles abgedeckt ist. Nur IDs, keine Inhalte (Req 16.7). */
    public static List<String> luecken(Path repo) {
        Map<String, Set<String>> tags = tags(repo.resolve("backend/src/test/resources/features"));
        List<String> luecken = new ArrayList<>();
        try (Stream<Path> specs = Files.list(repo.resolve(".kiro/specs"))) {
            for (Path spec : specs.sorted().toList()) {
                Path tasks = spec.resolve("tasks.md");
                Path requirements = spec.resolve("requirements.md");
                if (!Files.isRegularFile(tasks) || !Files.isRegularFile(requirements)) {
                    continue;
                }
                Set<String> nachweis = mitNachweis(lesen(requirements));
                Set<String> szenarien = tags.getOrDefault(spec.getFileName().toString().split("-", 2)[0], Set.of());
                for (String kriterium : gefordert(lesen(tasks))) {
                    if (!nachweis.contains(kriterium) && !szenarien.contains(kriterium)) {
                        luecken.add(spec.getFileName() + ": " + kriterium);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return luecken;
    }

    /** Kriterien aus {@code _Anforderungen_} abgehakter Aufgaben ab Welle 1. */
    static Set<String> gefordert(List<String> tasks) {
        Set<String> kriterien = new TreeSet<>();
        boolean zaehlt = false;
        for (String zeile : tasks) {
            if (zeile.startsWith("#")) {
                zaehlt = false;
                continue;
            }
            Matcher aufgabe = AUFGABE.matcher(zeile);
            if (aufgabe.find()) {
                String kennung = aufgabe.group(3);
                Matcher welle = WELLE.matcher(kennung);
                boolean optional = !aufgabe.group(2).isEmpty();
                if (!optional && !welle.matches() && !OPTIONAL.matcher(kennung).matches()) {
                    throw new IllegalArgumentException("Ungültige Aufgabenkennung: " + kennung);
                }
                zaehlt = !aufgabe.group(1).isBlank() && !optional
                        && welle.matches() && Integer.parseInt(welle.group(1)) >= 1;
                continue;
            }
            if (!zeile.contains("_Anforderungen")) {
                continue;
            }
            Matcher anforderungen = ANFORDERUNGEN.matcher(zeile);
            if (!anforderungen.matches()) {
                throw new IllegalArgumentException("Anforderungszeile nicht im Format «_Anforderungen: x.y, …_»: "
                        + zeile.strip());
            }
            List<String> liste = kriterien(anforderungen.group(1));
            if (zaehlt) {
                kriterien.addAll(liste);
            }
        }
        return kriterien;
    }

    /** «1.7, 16.20–16.22» → 1.7, 16.20, 16.21, 16.22; jedes Teil muss ganz passen, sonst Abbruch. */
    static List<String> kriterien(String liste) {
        List<String> ergebnis = new ArrayList<>();
        for (String teil : liste.split(",", -1)) {
            Matcher m = KRITERIEN.matcher(teil.strip());
            if (!m.matches()) {
                throw new IllegalArgumentException("Ungültige Anforderung: " + teil.strip());
            }
            int req = Integer.parseInt(m.group(1));
            int von = Integer.parseInt(m.group(2));
            int bis = m.group(5) == null ? von : Integer.parseInt(m.group(5));
            if (m.group(4) != null && Integer.parseInt(m.group(4)) != req) {
                throw new IllegalArgumentException("Bereich über mehrere Requirements: " + teil.strip());
            }
            if (von > bis) {
                throw new IllegalArgumentException("Bereich absteigend: " + teil.strip());
            }
            for (int k = von; k <= bis; k++) {
                ergebnis.add(req + "." + k);
            }
        }
        return ergebnis;
    }

    /** Kriterien, deren Text einen Nachweis-Vermerk trägt (tech.md, Tests und Qualität, Ausnahmen). */
    static Set<String> mitNachweis(List<String> requirements) {
        Set<String> ergebnis = new TreeSet<>();
        String requirement = null;
        String kriterium = null;
        for (String zeile : requirements) {
            Matcher kopf = REQUIREMENT.matcher(zeile);
            if (kopf.find()) {
                requirement = kopf.group(1);
                kriterium = null;
                continue;
            }
            if (zeile.startsWith("#")) {
                kriterium = null;
                continue;
            }
            Matcher nummer = KRITERIUM.matcher(zeile);
            if (requirement != null && nummer.find()) {
                kriterium = requirement + "." + nummer.group(1);
            }
            if (kriterium != null && NACHWEIS.matcher(zeile).find()) {
                ergebnis.add(kriterium);
            }
        }
        return ergebnis;
    }

    /** {@code @req}-Tags je Spec-Nummer («01»); bricht ab, wenn eine Feature-Datei nicht genau einen {@code @spec-NN} trägt. */
    static Map<String, Set<String>> tags(Path features) {
        Map<String, Set<String>> ergebnis = new TreeMap<>();
        if (!Files.isDirectory(features)) {
            return ergebnis;
        }
        try (Stream<Path> dateien = Files.walk(features)) {
            for (Path datei : dateien.filter(p -> p.toString().endsWith(".feature")).sorted().toList()) {
                List<String> specs = new ArrayList<>();
                Set<String> reqs = new TreeSet<>();
                boolean kopf = true;
                for (String zeile : lesen(datei)) {
                    if (FEATURE.matcher(zeile).find()) {
                        kopf = false;
                    }
                    if (!zeile.strip().startsWith("@")) {
                        continue;
                    }
                    Matcher spec = SPEC_TAG.matcher(zeile);
                    while (spec.find()) {
                        specs.add(kopf ? spec.group(1) : "Szenario");
                    }
                    Matcher req = TAG.matcher(zeile);
                    while (req.find()) {
                        reqs.add(req.group(1));
                    }
                }
                if (specs.size() != 1 || specs.getFirst().equals("Szenario")) {
                    throw new IllegalStateException("Feature-Datei braucht auf Feature-Ebene genau einen Tag @spec-NN: "
                            + features.relativize(datei));
                }
                ergebnis.computeIfAbsent(specs.getFirst(), s -> new TreeSet<>()).addAll(reqs);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ergebnis;
    }

    private static List<String> lesen(Path datei) {
        try {
            return Files.readAllLines(datei, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
