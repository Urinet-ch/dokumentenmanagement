package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Negativtests zur Secret-Prüfung der Workflows (Req 16.7, Nachbesserung PR #42). */
class WorkflowSecretsTest {

    private static final Set<String> ERLAUBT = Set.of("ARCHIV_DB_APP");

    @TempDir
    Path temporaer;

    @ParameterizedTest
    @ValueSource(strings = {
            "env:\n  X: ${{ secrets.ARCHIV_DB_APP }}",
            "env:\n  X: ${{ secrets.archiv_db_app }}",
            "run: printf '%s' \"$X\" > \"bundle/secrets/archiv_db_app\"",
            "run: ls bundle/secrets",
            "# Eine Datei je Secret"})
    void erlaubt(String workflow) {
        assertThat(WorkflowSecrets.funde(workflow, ERLAUBT)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "env:\n  X: ${{ toJSON(secrets.*) }}",
            "    secrets: inherit",
            "env:\n  X: ${{ secrets['ARCHIV_DB_APP'] }}",
            "env:\n  X: ${{ toJSON(secrets) }}",
            "env:\n  X: ${{ secrets.KEYCLOAK_ADMIN }}",
            "env:\n  X: ${{ secrets . KEYCLOAK_ADMIN }}",
            "env:\n  X: ${{ SECRETS.KEYCLOAK_ADMIN }}",
            "env:\n  X: ${{ format('{0}', secrets) }}",
            "run: cat bundle/secrets.json",
            "# Secrets im Fliesstext"})
    void unerlaubt(String workflow) {
        assertThat(WorkflowSecrets.funde(workflow, ERLAUBT)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"env:\n  X: ${{ secrets.ARCHIV_DB_APP }}", "run: ls bundle/secrets"})
    void ohneFreigabeIstJedesVorkommenEinFund(String workflow) {
        assertThat(WorkflowSecrets.funde(workflow, Set.of())).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET",
            "KEYCLOAK_VERWALTUNG_SECRET", "KEYCLOAK_SMTP_PASSWORT"})
    void keycloakSecretsNurImDeployErlaubt(String name) {
        String workflow = "env:\n  X: ${{ secrets." + name + " }}";
        assertThat(WorkflowSecrets.funde(workflow, WorkflowSecrets.ERLAUBT.get("infomaniak-deploy.yml"))).isEmpty();
        assertThat(WorkflowSecrets.funde(workflow, WorkflowSecrets.ERLAUBT.getOrDefault("ci.yml", Set.of())))
                .hasSize(1);
    }

    @Test
    void vorgeschlageneFormatpruefungPrueftDieWerteVorDemBundle() throws Exception {
        String skript = vorgeschlagenesSkript();
        String ed25519 = schluessel("ED25519");
        String andereKurve = schluessel("ED448");
        String gueltig = Base64.getEncoder().encodeToString(new byte[32]);
        assertThat(ausfuehren(skript, gueltig, "", ed25519)).isZero();
        assertThat(ausfuehren(skript, gueltig, gueltig, ed25519)).isZero();
        for (String falsch : List.of("", "abcd", Base64.getEncoder().encodeToString(new byte[33]), gueltig + "!",
                gueltig.substring(0, 10) + " " + gueltig.substring(10), gueltig.replace('A', '_'))) {
            assertThat(ausfuehren(skript, falsch, "", ed25519)).as("Ungültiges Salz").isNotZero();
            if (!falsch.isEmpty()) {
                assertThat(ausfuehren(skript, gueltig, falsch, ed25519)).as("Ungültiger Datenschlüssel").isNotZero();
            }
        }
        assertThat(ausfuehren(skript, gueltig, "", andereKurve)).isNotZero();
        assertThat(ausfuehren(skript, gueltig, "", "kein-schluessel")).isNotZero();
    }

    private String vorgeschlagenesSkript() throws Exception {
        Path workflow = temporaer.resolve(".github/workflows/infomaniak-deploy.yml");
        Files.createDirectories(workflow.getParent());
        Files.copy(GemeinsamSchritte.REPO.resolve(".github/workflows/infomaniak-deploy.yml"), workflow);
        Path diff = GemeinsamSchritte.REPO.resolve("docs/betrieb/secretformate-workflow.diff");
        if (gitApply(diff, "--check") == 0) {
            assertThat(gitApply(diff)).as("Workflow-Diff anwenden").isZero();
        } else {
            assertThat(gitApply(diff, "--reverse", "--check")).as("Workflow-Diff bereits übernommen").isZero();
        }
        String abschnitt = Files.readString(workflow).split("      - name: Geheimnisse und Variablen vorhanden\n", 2)[1]
                .split("\n      - uses:", 2)[0];
        assertThat(abschnitt).contains("ARCHIV_DATA_KEY: ${{ secrets.ARCHIV_DATA_KEY }}",
                "NAMENSSPERRE_SALZ: ${{ secrets.NAMENSSPERRE_SALZ }}",
                "ARCHIV_MANIFEST_KEY: ${{ secrets.ARCHIV_MANIFEST_KEY }}");
        return abschnitt.split("        run: \\|\n", 2)[1].lines()
                .map(zeile -> zeile.substring(10)).collect(Collectors.joining("\n"));
    }

    private int gitApply(Path diff, String... optionen) throws IOException, InterruptedException {
        var befehl = new java.util.ArrayList<>(List.of("git", "apply"));
        befehl.addAll(List.of(optionen));
        befehl.add(diff.toAbsolutePath().toString());
        Process prozess = new ProcessBuilder(befehl).directory(temporaer.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        return prozess.waitFor();
    }

    private static String schluessel(String algorithmus) throws IOException, InterruptedException {
        Process prozess = new ProcessBuilder("openssl", "genpkey", "-algorithm", algorithmus)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String schluessel = new String(prozess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(prozess.waitFor()).as("Synthetischen Schlüssel erzeugen").isZero();
        return schluessel;
    }

    private static int ausfuehren(String skript, String salz, String daten, String manifest)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("bash", "-e", "-o", "pipefail", "-c", skript)
                .redirectErrorStream(true);
        Map<String, String> umgebung = builder.environment();
        for (String name : List.of("SSH_KEY", "KNOWN_HOSTS", "ANDERE_APPS", "ARCHIV_DB_POSTGRES",
                "ARCHIV_DB_OWNER", "ARCHIV_DB_APP", "KEYCLOAK_DB", "KEYCLOAK_ADMIN", "S3_APP_ZUGANG_ID",
                "S3_APP_ZUGANG_GEHEIMNIS", "INFOMANIAK_AI_TOKEN", "KEYCLOAK_VERWALTUNG_SECRET",
                "KEYCLOAK_SMTP_PASSWORT")) {
            umgebung.put(name, "attrappe");
        }
        umgebung.put("NAMENSSPERRE_SALZ", salz);
        umgebung.put("ARCHIV_DATA_KEY", daten);
        umgebung.put("ARCHIV_MANIFEST_KEY", manifest);
        Process prozess = builder.start();
        String ausgabe = new String(prozess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        for (String wert : List.of(salz, daten, manifest)) {
            if (!wert.isEmpty()) {
                assertThat(ausgabe).doesNotContain(wert);
            }
        }
        return prozess.waitFor();
    }
}
