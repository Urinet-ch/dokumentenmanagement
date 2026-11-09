package ch.urinet.archiv.gemeinsam;

import io.cucumber.java.de.Dann;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zu den Leitplanken der Harness-Vorlage (Req 16.9, Aufgabe 1.8): Hooks laufen echt, nicht nur gelesen. */
public class LeitplankenSchritte {

    private static final Path REPO = GemeinsamSchritte.REPO;

    @Dann("prüft der Hook «{word}» jeden Commit mit gitleaks und bricht ohne gitleaks ab")
    public void preCommit(String hook) throws IOException, InterruptedException {
        assertThat(lesen(hook)).contains("gitleaks git --pre-commit --staged");
        // Ohne gitleaks im PATH darf der Commit nicht ungeprüft durchgehen
        assertThat(ausfuehren(hook, "", Map.of("PATH", "/usr/bin:/bin"))).isNotZero();
    }

    @Dann("prüft der Workflow «{word}» jeden Push auf allen Branches mit gitleaks")
    public void secretScan(String workflow) throws IOException {
        String inhalt = lesen(workflow);
        assertThat(inhalt).contains("branches: ['**']", "gitleaks git ");
    }

    @Dann("weist der Hook «{word}» einen Push auf «{word}» ab")
    public void prePush(String hook, String branch) throws IOException, InterruptedException {
        String sha = "0".repeat(40);
        assertThat(ausfuehren(hook, "refs/heads/x " + sha + " refs/heads/" + branch + " " + sha + "\n", Map.of()))
                .isNotZero();
        assertThat(ausfuehren(hook, "refs/heads/x " + sha + " refs/heads/feature/x " + sha + "\n", Map.of()))
                .isZero();
    }

    private static String lesen(String datei) throws IOException {
        return Files.readString(REPO.resolve(datei), StandardCharsets.UTF_8);
    }

    private static int ausfuehren(String hook, String eingabe, Map<String, String> umgebung)
            throws IOException, InterruptedException {
        ProcessBuilder befehl = new ProcessBuilder("/bin/bash", REPO.resolve(hook).toString())
                .directory(REPO.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        befehl.environment().putAll(umgebung);
        Process prozess = befehl.start();
        prozess.getOutputStream().write(eingabe.getBytes(StandardCharsets.UTF_8));
        prozess.getOutputStream().close();
        assertThat(prozess.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return prozess.exitValue();
    }
}
