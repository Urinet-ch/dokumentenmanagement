package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI-Nachweis des Deploys ({@code .github/scripts/pruefe-ci-nachweis.py}, Aufgabe 1.9, Review PR #42 Runde 2, S1):
 * Bis {@code PR_CI_BIS} gilt auch die grüne PR-CI des Squash-Merges, aber nur mit gleichem Stand (eingeführt, als Push
 * auf main ohne CI lief, PR #50; seit PR #58 läuft ci.yml wieder auf Push nach main, und Regel 3 braucht diesen Lauf). Bei reinen Spec-Anpassungen erbt ein Commit den Nachweis seines Vorgängers (Regel 3,
 * entschieden von Fabian am 13.11.2026; die Regel «nur Spec» selbst prüft {@link NurSpecTest}). Die GitHub-API ersetzt
 * ein {@code gh} auf dem PATH, das feste Antworten liefert.
 */
class CiNachweisTest {

    private static final Path SKRIPT = GemeinsamSchritte.REPO.resolve(".github/scripts/pruefe-ci-nachweis.py");
    private static final String R = "repos/Urinet-ch/dokumentenmanagement";
    private static final String SHA = "a".repeat(40);
    private static final String VORGAENGER = "b".repeat(40);
    private static final String KOPF = "c".repeat(40);
    private static final String BIS = "2026-12-01";
    private static final String VORHER = "2026-11-30";

    @TempDir
    Path tmp;
    private Path antworten;

    @BeforeEach
    void ghErsetzen() throws IOException {
        antworten = Files.createDirectories(tmp.resolve("antworten"));
        Path gh = tmp.resolve("bin/gh");
        Files.createDirectories(gh.getParent());
        Files.writeString(gh, """
                #!/bin/bash
                f="$GH_ANTWORTEN/$(printf %s "$2" | tr -c 'A-Za-z0-9._-' '_')"
                if [ -f "$f" ]; then cat "$f"; else echo "Not Found" >&2; exit 1; fi
                """);
        Files.setPosixFilePermissions(gh, PosixFilePermissions.fromString("rwx------"));
        antwort(R + "/actions/workflows/ci.yml/runs?head_sha=" + SHA + "&status=success&per_page=100", laeufe());
        antwort(R + "/commits/" + SHA, commit("t1", VORGAENGER));
        antwort(R + "/commits/" + SHA + "/pulls", """
                [{"number": 42, "merged_at": "2026-11-12T10:00:00Z", "merge_commit_sha": "%s",
                  "base": {"ref": "main"}, "head": {"sha": "%s"}}]""".formatted(SHA, KOPF));
        antwort(R + "/compare/" + VORGAENGER + "..." + KOPF, "{\"status\": \"ahead\"}");
        antwort(R + "/commits/" + KOPF, commit("t1", "d".repeat(40)));
        antwort(R + "/actions/workflows/ci.yml/runs?head_sha=" + KOPF + "&status=success&per_page=100&event=pull_request",
                laeufe(7));
        antwort(R + "/actions/runs/7/jobs?per_page=100", vollerLaufJobs());
    }

    @Test
    void gruenerLaufAufDemCommitGenuegt() throws Exception {
        antwort(R + "/actions/workflows/ci.yml/runs?head_sha=" + SHA + "&status=success&per_page=100", laeufe(5));
        antwort(R + "/actions/runs/5/jobs?per_page=100", vollerLaufJobs());
        Ergebnis e = pruefen("2027-01-15");
        assertThat(e.code()).as(e.ausgabe()).isZero();
        assertThat(e.ausgabe()).contains("Lauf 5 auf aaaaaaa");
    }

    @Test
    void laufVorDerRegelNurSpecGenuegt() throws Exception {
        // Läufe vor dem 13.11.2026 haben weder «Änderungsart» noch «Spec-Prüfungen»
        antwort(R + "/actions/workflows/ci.yml/runs?head_sha=" + SHA + "&status=success&per_page=100", laeufe(5));
        antwort(R + "/actions/runs/5/jobs?per_page=100", jobs("success", "success"));
        Ergebnis e = pruefen("2027-01-15");
        assertThat(e.code()).as(e.ausgabe()).isZero();
        assertThat(e.ausgabe()).contains("Lauf 5 auf aaaaaaa");
    }

    @Test
    void prCiMitGleichemStandGenuegtVorDemStichtag() throws Exception {
        Ergebnis e = pruefen(VORHER);
        assertThat(e.code()).as(e.ausgabe()).isZero();
        assertThat(e.ausgabe()).contains("PR #42", "Lauf 7");
    }

    @Test
    void abDemStichtagGiltNurDerLaufAufDemCommit() throws Exception {
        Ergebnis e = pruefen(BIS);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("gh workflow run ci.yml --ref main");
    }

    @Test
    void andererStandImPrKopfGiltNicht() throws Exception {
        antwort(R + "/commits/" + KOPF, commit("t2", "d".repeat(40)));
        Ergebnis e = pruefen(VORHER);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("anderen Stand", "gh workflow run ci.yml --ref main");
    }

    @Test
    void prKopfOhneVorgaengerAufMainGiltNicht() throws Exception {
        antwort(R + "/compare/" + VORGAENGER + "..." + KOPF, "{\"status\": \"diverged\"}");
        Ergebnis e = pruefen(VORHER);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("enthält den Vorgänger auf main nicht");
    }

    @Test
    void laufMitUebersprungenenJobsGiltNicht() throws Exception {
        // Ein PR als Entwurf: Lauf «success», alle Jobs übersprungen
        antwort(R + "/actions/runs/7/jobs?per_page=100", jobs("skipped", "skipped"));
        Ergebnis e = pruefen(VORHER);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("kein grüner CI-Lauf für Kopf");
    }

    @Test
    void ohneGemergtenPrGiltNichts() throws Exception {
        antwort(R + "/commits/" + SHA + "/pulls", "[]");
        Ergebnis e = pruefen(VORHER);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("kein PR auf main");
    }

    @Test
    void apiFehlerBrichtAb() throws Exception {
        Files.delete(antworten.resolve(datei(R + "/commits/" + SHA + "/pulls")));
        Ergebnis e = pruefen(VORHER);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("nicht prüfbar");
    }

    // Regel 3: reine Spec-Anpassung erbt den Nachweis des Vorgängers (entschieden von Fabian am 13.11.2026)
    private static final String NACH_STICHTAG = "2027-01-15";
    private static final String ART = "Änderungsart (nur Spec?)";
    private static final String SPEC = "Spec-Prüfungen (nur Spec)";
    private static final String BUILD = "Build (Quinoa, OpenAPI, Liquibase, Drive-Import)";
    private static final String CODE = "{\"filename\": \"backend/src/main/java/A.java\", \"status\": \"modified\"}";
    private static final String DOC = "{\"filename\": \"docs/a.md\", \"status\": \"modified\"}";

    @Test
    void vollerLaufMitUebersprungenemSchwerenJobIstKeinVollerLauf() throws Exception {
        antwort(laeufePfad(SHA), laeufeAuf(SHA, 5));
        antwort(R + "/actions/runs/5/jobs?per_page=100",
                vollerLaufJobs("Backend-Tests (Szenarien, JUnit, ArchUnit, SpotBugs)", "skipped"));
        aenderung(VORGAENGER, SHA, CODE);
        vollerLauf(VORGAENGER, 6);
        Ergebnis e = pruefen(NACH_STICHTAG);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("nicht nur Spec: backend/src/main/java/A.java");
    }

    @Test
    void vorDemStichtagErbtNurSpecWennDiePrCiNurSpecPruefungenHat() throws Exception {
        // PR #42 (Kopf KOPF) hat nur einen Lauf mit Spec-Prüfungen: Regel 2 greift nicht, Regel 3 schon
        antwort(R + "/actions/runs/7/jobs?per_page=100", specLaufJobs());
        specLauf(SHA, 5);
        aenderung(VORGAENGER, SHA, DOC);
        vollerLauf(VORGAENGER, 6);
        Ergebnis e = pruefen(VORHER);
        assertThat(e.code()).as(e.ausgabe()).isZero();
        assertThat(e.ausgabe()).contains("Lauf 5 auf aaaaaaa (nur Spec), erbt über bbbbbbb: Lauf 6 auf bbbbbbb");
    }

    @Test
    void vorDemStichtagErbtNurSpecDenNachweisDesVorgaengersAusDerPrCi() throws Exception {
        String eltern = "f".repeat(40);
        String kopf41 = "1".repeat(40);
        antwort(R + "/commits/" + SHA + "/pulls", "[]");
        specLauf(SHA, 5);
        aenderung(VORGAENGER, SHA, DOC);
        // Vorgänger ohne eigenen Lauf, aber Squash-Merge von PR #41 mit grüner PR-CI und gleichem Stand
        antwort(laeufePfad(VORGAENGER), "{\"workflow_runs\": []}");
        antwort(R + "/commits/" + VORGAENGER, commit("t0", eltern));
        antwort(R + "/commits/" + VORGAENGER + "/pulls", """
                [{"number": 41, "merged_at": "2026-11-12T09:00:00Z", "merge_commit_sha": "%s",
                  "base": {"ref": "main"}, "head": {"sha": "%s"}}]""".formatted(VORGAENGER, kopf41));
        antwort(R + "/compare/" + eltern + "..." + kopf41, "{\"status\": \"ahead\"}");
        antwort(R + "/commits/" + kopf41, commit("t0", eltern));
        antwort(laeufePfad(kopf41) + "&event=pull_request", laeufeAuf(kopf41, 8));
        antwort(R + "/actions/runs/8/jobs?per_page=100", vollerLaufJobs());
        Ergebnis e = pruefen(VORHER);
        assertThat(e.code()).as(e.ausgabe()).isZero();
        assertThat(e.ausgabe()).contains("Lauf 5 auf aaaaaaa (nur Spec), erbt über bbbbbbb: PR #41", "Lauf 8");
    }

    @Test
    void vergleichNichtVorausIstNichtBestimmbar() throws Exception {
        specLauf(SHA, 5);
        antwort(R + "/compare/" + VORGAENGER + "..." + SHA, "{\"status\": \"diverged\", \"files\": [" + DOC + "]}");
        vollerLauf(VORGAENGER, 6);
        Ergebnis e = pruefen(NACH_STICHTAG);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("nicht bestimmbar");
    }

    @Test
    void mergeCommitZaehltNurDenErstenElternteil() throws Exception {
        String zweiter = "9".repeat(40);
        specLauf(SHA, 5);
        antwort(R + "/commits/" + SHA, """
                {"commit": {"tree": {"sha": "t1"}}, "parents": [{"sha": "%s"}, {"sha": "%s"}]}""".formatted(VORGAENGER, zweiter));
        // zum ersten Elternteil kommt der Code des zweiten mit; zum zweiten wäre es nur Spec
        aenderung(VORGAENGER, SHA, DOC + "," + CODE);
        aenderung(zweiter, SHA, DOC);
        vollerLauf(VORGAENGER, 6);
        vollerLauf(zweiter, 9);
        Ergebnis e = pruefen(NACH_STICHTAG);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("Änderung zu bbbbbbb", "nicht nur Spec: backend/src/main/java/A.java");
    }

    @Test
    void ohneElternteilErbtNichts() throws Exception {
        specLauf(SHA, 5);
        antwort(R + "/commits/" + SHA, "{\"commit\": {\"tree\": {\"sha\": \"t1\"}}, \"parents\": []}");
        Ergebnis e = pruefen(NACH_STICHTAG);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("kein Vorgänger");
    }

    @Test
    void nurSpecErbtDenNachweisDesVorgaengers() throws Exception {
        specLauf(SHA, 5);
        aenderung(VORGAENGER, SHA, "{\"filename\": \".kiro/specs/01-archiv-kern/design.md\", \"status\": \"modified\"}");
        vollerLauf(VORGAENGER, 6);
        Ergebnis e = pruefen(NACH_STICHTAG);
        assertThat(e.code()).as(e.ausgabe()).isZero();
        assertThat(e.ausgabe()).contains("Lauf 5 auf aaaaaaa (nur Spec)", "Lauf 6 auf bbbbbbb");
    }

    @Test
    void nurSpecOhneNachweisDesVorgaengersGiltNicht() throws Exception {
        specLauf(SHA, 5);
        aenderung(VORGAENGER, SHA, "{\"filename\": \"docs/a.md\", \"status\": \"removed\"}");
        antwort(laeufePfad(VORGAENGER), "{\"workflow_runs\": []}");
        Ergebnis e = pruefen(NACH_STICHTAG);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("ohne Nachweis in der Kette", "kein grüner Lauf auf bbbbbbb",
                "gh workflow run ci.yml --ref main");
    }

    @Test
    void umbenennungAusDemCodeErbtNicht() throws Exception {
        specLauf(SHA, 5);
        aenderung(VORGAENGER, SHA, """
                {"filename": "docs/Notiz.md", "previous_filename": "backend/Notiz.md", "status": "renamed"}""");
        vollerLauf(VORGAENGER, 6);
        Ergebnis e = pruefen(NACH_STICHTAG);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("nicht nur Spec: backend/Notiz.md");
    }

    @Test
    void zuVieleDateienSindNichtBestimmbar() throws Exception {
        specLauf(SHA, 5);
        aenderung(VORGAENGER, SHA, String.join(",", java.util.stream.IntStream.range(0, 300)
                .mapToObj(i -> "{\"filename\": \"docs/" + i + ".md\", \"status\": \"added\"}").toList()));
        vollerLauf(VORGAENGER, 6);
        Ergebnis e = pruefen(NACH_STICHTAG);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("nicht bestimmbar");
    }

    @Test
    void laufOhneErfolgreicheSpecPruefungGiltNicht() throws Exception {
        // Entwurf: alles übersprungen, auch die Spec-Prüfungen
        antwort(laeufePfad(SHA), laeufeAuf(SHA, 5));
        antwort(R + "/actions/runs/5/jobs?per_page=100", benannteJobs(ART, "skipped", SPEC, "skipped", "Build", "skipped"));
        aenderung(VORGAENGER, SHA, "{\"filename\": \"docs/a.md\", \"status\": \"modified\"}");
        vollerLauf(VORGAENGER, 6);
        Ergebnis e = pruefen(NACH_STICHTAG);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("kein grüner Lauf auf aaaaaaa");
    }

    @Test
    void ketteBisZurHoechsttiefe() throws Exception {
        kette(20);
        Ergebnis e = pruefen(NACH_STICHTAG);
        assertThat(e.code()).as(e.ausgabe()).isZero();
        assertThat(e.ausgabe()).contains("Lauf 1020 auf " + glied(20).substring(0, 7));
    }

    @Test
    void ketteUeberDieHoechsttiefeBrichtAb() throws Exception {
        kette(21);
        Ergebnis e = pruefen(NACH_STICHTAG);
        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("mehr als 20 Commits in Folge", "Höchsttiefe", "gh workflow run ci.yml --ref main");
    }

    /** SHA = Glied 0 erbt über n Commits nur mit Spec-Prüfungen von Glied n, das einen vollen Lauf hat. */
    private void kette(int n) throws IOException {
        for (int i = 0; i < n; i++) {
            specLauf(glied(i), 1000 + i);
            antwort(R + "/commits/" + glied(i), commit("t" + i, glied(i + 1)));
            aenderung(glied(i + 1), glied(i), "{\"filename\": \"docs/" + i + ".md\", \"status\": \"modified\"}");
        }
        vollerLauf(glied(n), 1000 + n);
    }

    private static String glied(int i) {
        return i == 0 ? SHA : "e%039x".formatted(i);
    }

    private void specLauf(String sha, long id) throws IOException {
        antwort(laeufePfad(sha), laeufeAuf(sha, id));
        antwort(R + "/actions/runs/" + id + "/jobs?per_page=100", specLaufJobs());
    }

    private void vollerLauf(String sha, long id) throws IOException {
        antwort(laeufePfad(sha), laeufeAuf(sha, id));
        antwort(R + "/actions/runs/" + id + "/jobs?per_page=100", vollerLaufJobs());
    }

    /** Jobs eines vollen Laufs wie in ci.yml: alle erfolgreich, auch Änderungsart und Spec-Prüfungen. */
    private static String vollerLaufJobs(String... abweichend) {
        java.util.Map<String, String> jobs = new java.util.LinkedHashMap<>();
        for (String name : List.of(ART, SPEC, BUILD, "Backend-Tests (Szenarien, JUnit, ArchUnit, SpotBugs)",
                "Backend-Starttests (start-1)", "Backend-Starttests (start-2)",
                "Backend-Tests vollständig (jede Klasse genau einmal)", "Frontend (ng test)",
                "Browser-Szenarien (Cucumber.js, Playwright)", "Docker-Image (nur bauen)")) {
            jobs.put(name, "success");
        }
        for (int i = 0; i < abweichend.length; i += 2) {
            jobs.put(abweichend[i], abweichend[i + 1]);
        }
        return benannteJobs(jobs.entrySet().stream().flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue()))
                .toArray(String[]::new));
    }

    /** Jobs eines Laufs nur mit Spec-Prüfungen: alle übrigen übersprungen. */
    private static String specLaufJobs() {
        return vollerLaufJobs(BUILD, "skipped", "Backend-Tests (Szenarien, JUnit, ArchUnit, SpotBugs)", "skipped",
                "Backend-Starttests (start-1)", "skipped", "Backend-Starttests (start-2)", "skipped",
                "Backend-Tests vollständig (jede Klasse genau einmal)", "skipped", "Frontend (ng test)", "skipped",
                "Browser-Szenarien (Cucumber.js, Playwright)", "skipped", "Docker-Image (nur bauen)", "skipped");
    }

    private void aenderung(String basis, String sha, String dateien) throws IOException {
        antwort(R + "/compare/" + basis + "..." + sha, "{\"status\": \"ahead\", \"files\": [" + dateien + "]}");
    }

    private static String laeufePfad(String sha) {
        return R + "/actions/workflows/ci.yml/runs?head_sha=" + sha + "&status=success&per_page=100";
    }

    private static String laeufeAuf(String sha, long id) {
        return """
                {"workflow_runs": [{"id": %d, "head_sha": "%s", "conclusion": "success"}]}""".formatted(id, sha);
    }

    private static String benannteJobs(String... nameUndErgebnis) {
        List<String> l = new java.util.ArrayList<>();
        for (int i = 0; i < nameUndErgebnis.length; i += 2) {
            l.add("{\"name\": \"%s\", \"conclusion\": \"%s\"}".formatted(nameUndErgebnis[i], nameUndErgebnis[i + 1]));
        }
        return "{\"jobs\": [" + String.join(",", l) + "]}";
    }

    private record Ergebnis(int code, String ausgabe) {
    }

    private Ergebnis pruefen(String heute) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("python3", SKRIPT.toString(), "Urinet-ch/dokumentenmanagement", SHA, BIS,
                heute).redirectErrorStream(true);
        pb.environment().put("PATH", tmp.resolve("bin") + ":" + System.getenv("PATH"));
        pb.environment().put("GH_ANTWORTEN", antworten.toString());
        Process p = pb.start();
        String ausgabe = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new Ergebnis(p.exitValue(), ausgabe);
    }

    private void antwort(String pfad, String json) throws IOException {
        Files.writeString(antworten.resolve(datei(pfad)), json);
    }

    private static String datei(String pfad) {
        return pfad.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String laeufe(long... ids) {
        List<String> l = java.util.Arrays.stream(ids).mapToObj(id -> """
                {"id": %d, "head_sha": "%s", "conclusion": "success"}""".formatted(id, id == 5 ? SHA : KOPF)).toList();
        return "{\"workflow_runs\": [" + String.join(",", l) + "]}";
    }

    private static String jobs(String... ergebnisse) {
        List<String> l = java.util.Arrays.stream(ergebnisse).map(c -> "{\"conclusion\": \"" + c + "\"}").toList();
        return "{\"jobs\": [" + String.join(",", l) + "]}";
    }

    private static String commit(String tree, String vorgaenger) {
        return """
                {"commit": {"tree": {"sha": "%s"}}, "parents": [{"sha": "%s"}]}""".formatted(tree, vorgaenger);
    }
}
