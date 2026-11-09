package ch.urinet.archiv.gemeinsam;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Req 16.14 für die CI: Jedes Kriterium, das eine abgehakte Aufgabe ab Welle 1 unter {@code _Anforderungen_} nennt,
 * hat ein Szenario mit Tag {@code @req-x.y} oder einen Nachweis-Vermerk («manuell», «Dokument», «JUnit»).
 * Welle 0 bleibt aussen vor, weil ihre Kriterien Spikes und Golden-Läufe betreffen, nicht das Produkt.
 */
public final class ReqAbdeckung {

    private static final Pattern AUFGABE = Pattern.compile("^- \\[([ xX])] (\\d+)\\.\\d+\\b");
    private static final Pattern ANFORDERUNGEN = Pattern.compile("_Anforderungen: ([^_]*)_");
    private static final Pattern KRITERIEN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\s*[–-]\\s*(?:(\\d+)\\.)?(\\d+))?");
    private static final Pattern REQUIREMENT = Pattern.compile("^#{2,4} Requirement (\\d+)\\b");
    private static final Pattern KRITERIUM = Pattern.compile("^(\\d+)\\. ");
    private static final Pattern NACHWEIS = Pattern.compile("Nachweis: (manuell|Dokument|JUnit)");
    private static final Pattern TAG = Pattern.compile("@req-(\\d+\\.\\d+)\\b");

    private ReqAbdeckung() {
    }

    /** Fehlende Abdeckung als «spec: x.y», sortiert; leer, wenn alles abgedeckt ist. Nur IDs, keine Inhalte (Req 16.7). */
    public static List<String> luecken(Path repo) {
        Set<String> tags = tags(repo.resolve("backend/src/test/resources/features"));
        List<String> luecken = new ArrayList<>();
        try (Stream<Path> specs = Files.list(repo.resolve(".kiro/specs"))) {
            for (Path spec : specs.sorted().toList()) {
                Path tasks = spec.resolve("tasks.md");
                Path requirements = spec.resolve("requirements.md");
                if (!Files.isRegularFile(tasks) || !Files.isRegularFile(requirements)) {
                    continue;
                }
                Set<String> nachweis = mitNachweis(lesen(requirements));
                for (String kriterium : gefordert(lesen(tasks))) {
                    if (!nachweis.contains(kriterium) && !tags.contains(kriterium)) {
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
            Matcher aufgabe = AUFGABE.matcher(zeile);
            if (aufgabe.find()) {
                zaehlt = !aufgabe.group(1).isBlank() && Integer.parseInt(aufgabe.group(2)) >= 1;
                continue;
            }
            Matcher anforderungen = ANFORDERUNGEN.matcher(zeile);
            if (zaehlt && anforderungen.find()) {
                kriterien.addAll(kriterien(anforderungen.group(1)));
            }
        }
        return kriterien;
    }

    /** «1.7, 16.20–16.22» → 1.7, 16.20, 16.21, 16.22. */
    static List<String> kriterien(String liste) {
        List<String> ergebnis = new ArrayList<>();
        Matcher m = KRITERIEN.matcher(liste);
        while (m.find()) {
            int req = Integer.parseInt(m.group(1));
            int von = Integer.parseInt(m.group(2));
            int bis = m.group(4) == null ? von : Integer.parseInt(m.group(4));
            if (m.group(3) != null && Integer.parseInt(m.group(3)) != req) {
                throw new IllegalArgumentException("Bereich über mehrere Requirements: " + m.group());
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

    static Set<String> tags(Path features) {
        Set<String> ergebnis = new TreeSet<>();
        if (!Files.isDirectory(features)) {
            return ergebnis;
        }
        try (Stream<Path> dateien = Files.walk(features)) {
            for (Path datei : dateien.filter(p -> p.toString().endsWith(".feature")).toList()) {
                for (String zeile : lesen(datei)) {
                    if (zeile.strip().startsWith("@")) {
                        Matcher m = TAG.matcher(zeile);
                        while (m.find()) {
                            ergebnis.add(m.group(1));
                        }
                    }
                }
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
