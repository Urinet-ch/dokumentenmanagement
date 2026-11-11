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
 * Solange Push auf main ohne CI läuft (bis {@code PR_CI_BIS}), gilt auch die grüne PR-CI des Squash-Merges, aber nur
 * mit gleichem Stand. Die GitHub-API ersetzt ein {@code gh} auf dem PATH, das feste Antworten liefert.
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
        antwort(R + "/actions/runs/7/jobs?per_page=100", jobs("success", "success"));
    }

    @Test
    void gruenerLaufAufDemCommitGenuegt() throws Exception {
        antwort(R + "/actions/workflows/ci.yml/runs?head_sha=" + SHA + "&status=success&per_page=100", laeufe(5));
        antwort(R + "/actions/runs/5/jobs?per_page=100", jobs("success", "success"));
        Ergebnis e = pruefen("2027-01-15");
        assertThat(e.code()).isZero();
        assertThat(e.ausgabe()).contains("Lauf 5");
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
