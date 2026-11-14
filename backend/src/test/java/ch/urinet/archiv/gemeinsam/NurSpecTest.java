package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regel «nur Spec» ({@code .github/scripts/nur-spec.py}): Bei reinen Spec-Anpassungen läuft in ci.yml nur der Job
 * «Spec-Prüfungen», und der Deploy-Nachweis erbt den des Vorgängers (entschieden von Fabian am 13.11.2026). Nur
 * .md-Dateien unter .kiro/ oder docs/ zählen, bei Umbenennungen beide Pfade; im Zweifel voller Lauf.
 */
class NurSpecTest {

    private static final Path REPO = GemeinsamSchritte.REPO;
    private static final Path SKRIPT = REPO.resolve(".github/scripts/nur-spec.py");
    private static final String NULL = "0".repeat(40);

    @TempDir
    Path tmp;

    @Test
    void specUndDocsAlsMarkdownSindNurSpec() throws Exception {
        assertThat(pfade(".kiro/specs/01-archiv-kern/design.md", ".kiro/steering/tech.md", "docs/adr/adr-001.md",
                "docs/umsetzungsplan.md")).isEqualTo("true");
        assertThat(pfade("docs/Grüne Übersicht mit Leerzeichen.md", ".kiro/specs/02 Abgleich/Ä ö ü – Notiz.md"))
                .isEqualTo("true");
    }

    @Test
    void allesAndereVerlangtDenVollenLauf() throws Exception {
        for (String pfad : List.of("README.md", "AGENTS.md", "CLAUDE.md", "backend/README.md", ".github/x.md",
                "tools/README.md", "docs/manifest-schluessel.pub.pem", "docs/adr/bild.png", ".kiro/settings/mcp.json",
                "docs/x.MD", "docs/x.md.txt", "docsx/a.md", "kiro/a.md", "x/docs/a.md", "docs/../backend/a.md",
                "docs/./a.md", "docs//a.md", "docs/a\nb.md", ".github/workflows/ci.yml",
                "backend/src/main/java/ch/urinet/archiv/A.java", "docs/Grün mit Leerzeichen.txt")) {
            assertThat(pfade("docs/a.md", pfad)).as(pfad).isEqualTo("false");
        }
    }

    @Test
    void leereListeIstNichtNurSpec() throws Exception {
        assertThat(pfade()).isEqualTo("false");
    }

    @Test
    void ohnePrUndPushNieNurSpec() throws Exception {
        assertThat(lauf(null, "workflow_dispatch").ausgabe()).isEqualTo("false");
        assertThat(lauf(null).ausgabe()).isEqualTo("false");
    }

    @Test
    void pushNurMitSpecAenderung() throws Exception {
        Path repo = repo();
        String vorher = commit(repo, "docs/a.md", "x", "backend/A.java", "class A {}");
        String nachher = commit(repo, "docs/a.md", "y", ".kiro/specs/01 Kern/Grüne Übersicht.md", "neu");
        assertThat(regel(repo, "push", vorher, nachher)).isEqualTo("true");
    }

    @Test
    void umbenennungAusDemCodeOderInDenCodeIstNichtNurSpec() throws Exception {
        Path repo = repo();
        String basis = commit(repo, "backend/Notiz.md", "gleicher Inhalt", "docs/b.md", "zweiter Inhalt");
        verschieben(repo, "backend/Notiz.md", "docs/Notiz.md");
        String ausCode = commit(repo);
        assertThat(regel(repo, "push", basis, ausCode)).as("aus dem Code").isEqualTo("false");
        verschieben(repo, "docs/b.md", "backend/b.md");
        String inCode = commit(repo);
        assertThat(regel(repo, "push", ausCode, inCode)).as("in den Code").isEqualTo("false");
        verschieben(repo, "docs/Notiz.md", "docs/adr/Notiz.md");
        assertThat(regel(repo, "push", inCode, commit(repo))).as("innerhalb docs/").isEqualTo("true");
    }

    @Test
    void loeschungZaehlt() throws Exception {
        Path repo = repo();
        String basis = commit(repo, "docs/a.md", "x", "backend/A.java", "class A {}");
        Files.delete(repo.resolve("backend/A.java"));
        String ohneCode = commit(repo);
        assertThat(regel(repo, "push", basis, ohneCode)).isEqualTo("false");
        Files.delete(repo.resolve("docs/a.md"));
        Files.writeString(repo.resolve("docs/b.md"), "y");
        assertThat(regel(repo, "push", ohneCode, commit(repo))).isEqualTo("true");
    }

    @Test
    void pemUnterDocsIstNichtNurSpec() throws Exception {
        Path repo = repo();
        String basis = commit(repo, "docs/a.md", "x");
        assertThat(regel(repo, "push", basis, commit(repo, "docs/manifest-schluessel.pub.pem", "schluessel")))
                .isEqualTo("false");
    }

    @Test
    void neuerBranchUnbekannterOderUeberschriebenerVorgaengerNieNurSpec() throws Exception {
        Path repo = repo();
        String a = commit(repo, "docs/a.md", "x");
        String b = commit(repo, "docs/a.md", "y");
        assertThat(regel(repo, "push", NULL, b)).as("neuer Branch").isEqualTo("false");
        assertThat(regel(repo, "push", "", b)).as("ohne before").isEqualTo("false");
        assertThat(regel(repo, "push", "f".repeat(40), b)).as("unbekannt").isEqualTo("false");
        assertThat(regel(repo, "push", b, a)).as("kein Vorfahr (überschrieben)").isEqualTo("false");
        assertThat(regel(repo, "push", a, a)).as("keine Änderung").isEqualTo("false");
        assertThat(regel(repo, "push", a, b)).isEqualTo("true");
    }

    @Test
    void prVergleichtAbDerAbzweigung() throws Exception {
        Path repo = repo();
        String basis = commit(repo, "docs/a.md", "x", "backend/A.java", "class A {}");
        git(repo, "checkout", "-q", "-b", "pr");
        String kopf = commit(repo, "docs/a.md", "y");
        git(repo, "checkout", "-q", "main");
        // main bewegt sich mit Code weiter; der PR selbst ändert nur Spec
        String mainNeu = commit(repo, "backend/A.java", "class A { int x; }");
        assertThat(regel(repo, "pr", mainNeu, kopf)).isEqualTo("true");
        assertThat(regel(repo, "pr", basis, kopf)).isEqualTo("true");
        git(repo, "checkout", "-q", "pr");
        String kopfMitCode = commit(repo, "backend/B.java", "class B {}");
        assertThat(regel(repo, "pr", mainNeu, kopfMitCode)).isEqualTo("false");
    }

    /** Die Positivliste gilt nur, solange kein Build-Schritt eine .md-Datei aus .kiro/ oder docs/ übernimmt. */
    @Test
    void keinBuildUebernimmtMarkdownAusSpecOderDocs() throws IOException {
        String pom = Files.readString(REPO.resolve("backend/pom.xml"), StandardCharsets.UTF_8);
        Matcher ressource = Pattern.compile("<resource>(.*?)</resource>", Pattern.DOTALL).matcher(pom);
        int ausDocs = 0;
        while (ressource.find()) {
            String block = ressource.group(1);
            if (!block.contains("docs") && !block.contains(".kiro")) {
                continue;
            }
            ausDocs++;
            List<String> includes = Pattern.compile("<include>([^<]*)</include>").matcher(block).results()
                    .map(m -> m.group(1).trim()).toList();
            assertThat(includes).as("Ressource aus docs/ oder .kiro/ nur mit festen Dateien").isNotEmpty()
                    .allSatisfy(i -> assertThat(i).doesNotContain("*").doesNotEndWith(".md"));
        }
        assertThat(ausDocs).as("Ressource mit dem Manifest-Schlüssel aus docs/").isEqualTo(1);
        for (String datei : List.of("tools/drive_import/pom.xml", "frontend/angular.json", "frontend/package.json",
                "backend/src/main/docker/Dockerfile.jvm", "deploy/infomaniak/db/Dockerfile",
                "deploy/infomaniak/keycloak/Dockerfile", "backend/src/main/resources/application.properties")) {
            assertThat(Files.readString(REPO.resolve(datei), StandardCharsets.UTF_8)).as(datei)
                    .doesNotContain(".kiro", "../docs");
        }
    }

    /** ci.yml und Deploy-Nachweis erkennen den Lauf an denselben Job-Namen; jede Spec-Testklasse gibt es. */
    @Test
    void ciNenntDieJobsUndSpecTestsAusDerRegel() throws IOException {
        String regel = Files.readString(SKRIPT, StandardCharsets.UTF_8);
        String ci = Files.readString(REPO.resolve(".github/workflows/ci.yml"), StandardCharsets.UTF_8);
        for (String konstante : List.of("SPEC_JOB", "ART_JOB")) {
            Matcher m = Pattern.compile("^" + konstante + " = \"([^\"]+)\"$", Pattern.MULTILINE).matcher(regel);
            assertThat(m.find()).as(konstante).isTrue();
            assertThat(ci).as(konstante).contains("    name: " + m.group(1) + "\n");
        }
        Matcher tests = Pattern.compile("SPEC_TESTS: (\\S+)").matcher(ci);
        assertThat(tests.find()).isTrue();
        List<String> vorhanden;
        try (Stream<Path> dateien = Files.walk(REPO.resolve("backend/src/test/java"))) {
            vorhanden = dateien.map(p -> p.getFileName().toString().replace(".java", "")).toList();
        }
        assertThat(Arrays.asList(tests.group(1).split(","))).isNotEmpty().allSatisfy(t -> assertThat(vorhanden).contains(t));
    }

    /**
     * Regel 3 des Deploy-Nachweises braucht den Lauf auf dem Push nach main; die Spec-Prüfungen laufen in jedem Lauf
     * (sonst wären sie im vollen Lauf übersprungen und kein voller Lauf mehr «jeder Job erfolgreich»); die Regel kommt
     * aus dem Basis-Stand, nicht aus dem geprüften Commit (Review 13.11.2026).
     */
    @Test
    void ciLaeuftAufPushUndSpecPruefungenImmer() throws IOException {
        String ci = Files.readString(REPO.resolve(".github/workflows/ci.yml"), StandardCharsets.UTF_8);
        assertThat(ci).contains("\n  push:\n    branches: [main]\n");
        Matcher spec = Pattern.compile("\n  spec:\n(.*?)\n  [a-z]+:\n", Pattern.DOTALL).matcher(ci);
        assertThat(spec.find()).isTrue();
        assertThat(spec.group(1)).contains("    if: github.event_name != 'pull_request' || !github.event.pull_request.draft\n")
                .doesNotContain("needs:", "outputs.nur-spec");
        assertThat(ci).contains("git show \"$stand:.github/scripts/nur-spec.py\"")
                .doesNotContain("python3 .github/scripts/nur-spec.py");
    }

    /** Hilfsklassen, die Specs lesen: Nutzer ohne Eintrag in SPEC_TESTS nur mit Grund. */
    private static final java.util.Map<String, String> NUTZER_AUSSERHALB = java.util.Map.of(
            // Cucumber-Schritt «Seed = Tabelle in design.md» über die Datenbank; das JUnit-Gegenstück DokumentartSeedTest
            // prüft Seed gegen Tabelle ohne Start
            "DokumentartSchritte", "DokumentartSeedTest",
            // Cucumber-Schritt «Abdeckung @req»; ReqAbdeckungTest.repositoryIstAbgedeckt prüft dasselbe ohne Start
            "GemeinsamSchritte", "ReqAbdeckungTest");

    /**
     * Testklassen, die unter docs/ nur Nicht-Markdown lesen: Eine Änderung daran ist nie «nur Spec», der volle Lauf führt
     * sie aus; darum brauchen sie keinen Eintrag in SPEC_TESTS (Spec 03, Aufgabe 11.6). Ihre Quelle nennt keine .md-Datei.
     */
    private static final java.util.Map<String, String> OHNE_MARKDOWN = java.util.Map.of(
            "PublicVertragTest", "docs/schnittstelle/public-v1.yaml (Vertrag der Webseiten-Schnittstelle)");

    /**
     * Jede Testklasse mit einem Pfad nach .kiro/ oder docs/ steht in SPEC_TESTS; ist sie keine Testklasse (Hilfstyp wie
     * StammdatenTabelle, ReqAbdeckung), dann jeder ihrer Nutzer, ausser er steht mit Gegenstück in NUTZER_AUSSERHALB.
     * Sonst liefe ein neuer Spec-Test bei reinen Spec-Anpassungen nie (Review 13.11.2026).
     */
    @Test
    void specTestsSindVollstaendig() throws IOException {
        String ci = Files.readString(REPO.resolve(".github/workflows/ci.yml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("SPEC_TESTS: (\\S+)").matcher(ci);
        assertThat(m.find()).isTrue();
        List<String> specTests = Arrays.asList(m.group(1).split(","));
        Pattern pfad = Pattern.compile("\"(?:\\.\\./)*(?:\\.kiro|docs)(?:/|\")");
        java.util.Map<String, String> quellen = new java.util.TreeMap<>();
        try (Stream<Path> dateien = Files.walk(REPO.resolve("backend/src/test/java"))) {
            for (Path datei : dateien.filter(p -> p.toString().endsWith(".java")).toList()) {
                quellen.put(datei.getFileName().toString().replace(".java", ""),
                        Files.readString(datei, StandardCharsets.UTF_8));
            }
        }
        List<String> leser = quellen.entrySet().stream().filter(e -> pfad.matcher(e.getValue()).find())
                .map(java.util.Map.Entry::getKey).toList();
        assertThat(leser).as("Klassen mit Pfad nach .kiro/ oder docs/").contains("StammdatenTabelle", "ReqAbdeckung",
                "FehlercodeSpecTest", "RechtematrixSpecTest");
        for (String klasse : leser) {
            if (specTests.contains(klasse)) {
                continue;
            }
            if (OHNE_MARKDOWN.containsKey(klasse)) {
                assertThat(quellen.get(klasse)).as(klasse + " liest nur " + OHNE_MARKDOWN.get(klasse))
                        .doesNotContain(".md\"", ".kiro");
                continue;
            }
            assertThat(klasse).as(klasse + " liest .kiro/ oder docs/, ist aber nicht in SPEC_TESTS").doesNotEndWith("Test");
            Pattern verweis = Pattern.compile("\\b" + klasse + "\\b");
            List<String> nutzer = quellen.entrySet().stream()
                    .filter(e -> !e.getKey().equals(klasse) && verweis.matcher(e.getValue()).find())
                    .map(java.util.Map.Entry::getKey).toList();
            assertThat(nutzer).as("Nutzer von " + klasse).isNotEmpty().allSatisfy(n -> assertThat(
                    specTests.contains(n) || specTests.contains(NUTZER_AUSSERHALB.get(n)))
                    .as(n + " nutzt " + klasse + ", ist aber nicht in SPEC_TESTS").isTrue());
        }
    }

    private record Ergebnis(int code, String ausgabe) {
    }

    private String pfade(String... pfade) throws Exception {
        Ergebnis e = lauf(String.join("\0", pfade), "pfade");
        assertThat(e.code()).isZero();
        return e.ausgabe();
    }

    private Ergebnis lauf(String stdin, String... argumente) throws Exception {
        return ausfuehren(tmp, stdin, befehl(argumente));
    }

    private static String regel(Path repo, String art, String basis, String kopf) throws Exception {
        Ergebnis e = ausfuehren(repo, null, befehl(art, basis, kopf));
        assertThat(e.code()).isZero();
        return e.ausgabe();
    }

    private static List<String> befehl(String... argumente) {
        List<String> befehl = new ArrayList<>(List.of("python3", SKRIPT.toString()));
        befehl.addAll(Arrays.asList(argumente));
        return befehl;
    }

    private static Ergebnis ausfuehren(Path verzeichnis, String stdin, List<String> befehl) throws Exception {
        Process p = new ProcessBuilder(befehl).directory(verzeichnis.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var ein = p.getOutputStream()) {
            if (stdin != null) {
                ein.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }
        String ausgabe = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new Ergebnis(p.exitValue(), ausgabe);
    }

    private Path repo() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        git(repo, "init", "-q", "-b", "main");
        return repo;
    }

    /** Schreibt Pfad/Inhalt-Paare, committet alles und liefert den SHA. */
    private static String commit(Path repo, String... pfadUndInhalt) throws Exception {
        for (int i = 0; i < pfadUndInhalt.length; i += 2) {
            Path datei = repo.resolve(pfadUndInhalt[i]);
            Files.createDirectories(datei.getParent());
            Files.writeString(datei, pfadUndInhalt[i + 1], StandardCharsets.UTF_8);
        }
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false",
                "commit", "-q", "--allow-empty", "-m", "Test");
        return git(repo, "rev-parse", "HEAD");
    }

    private static void verschieben(Path repo, String von, String nach) throws Exception {
        Files.createDirectories(repo.resolve(nach).getParent());
        git(repo, "mv", von, nach);
    }

    private static String git(Path repo, String... argumente) throws Exception {
        List<String> befehl = new ArrayList<>(List.of("git"));
        befehl.addAll(Arrays.asList(argumente));
        Ergebnis e = ausfuehren(repo, null, befehl);
        assertThat(e.code()).as(String.join(" ", befehl)).isZero();
        return e.ausgabe();
    }
}
