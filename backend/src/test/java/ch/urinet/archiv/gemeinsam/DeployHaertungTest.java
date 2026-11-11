package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Härtung des Deploys nach der Compose-Policy der VPS (tech.md «Plattform und Betrieb», Aufgabe 1.9, Review PR #42
 * S1 und S2). Die Policy des Infra-Repos prüft Limits, {@code cap_drop}, {@code no-new-privileges} und die uid nur für
 * Apps auf {@code -api}, nicht für {@code archiv}; darum prüft das Repo sie selbst.
 */
class DeployHaertungTest {

    private static final Path REPO = GemeinsamSchritte.REPO;
    private static final Path COMPOSE = REPO.resolve("deploy/infomaniak/compose.yml");
    private static final Path WORKFLOW = REPO.resolve(".github/workflows/infomaniak-deploy.yml");
    private static final Pattern UID = Pattern.compile("[1-9]\\d*(:\\d+)?");
    private static final Pattern MEM = Pattern.compile("(\\d+)([kmg]?)b?");
    /** Schreibt in eine Datei (nicht {@code >&}, nicht {@code /dev/null}). */
    /** Schreibt eine Datei: Umleitung (auch {@code 2>}) ausser nach /dev/null, auf einen Deskriptor oder in die
     *  Dateien von Actions ({@code $GITHUB_OUTPUT} u. a.), oder ein Befehl, der Dateien anlegt. */
    private static final Pattern SCHREIBT = Pattern.compile(
            "(?<![<>&])\\d?>{1,2}(?![>&])\\s*(?!/dev/null|\"?\\$\\{?GITHUB_(?:OUTPUT|ENV|PATH|STEP_SUMMARY)\\b)\\S"
                    + "|\\b(?:tee|cp|mv|mkdir|install|touch)\\s|\\bdocker\\s+save\\b"
                    + "|\\bcurl\\b[^\\n]*\\s-o\\s*(?!/dev/null)\\S");

    @Test
    void jederDienstGehaertet() throws IOException {
        List<String> fehler = new ArrayList<>();
        dienste().forEach((name, dienst) -> {
            if (!(dienst.get("cap_drop") instanceof List<?> drop) || !drop.contains("ALL")) {
                fehler.add(name + ": cap_drop ohne ALL");
            }
            if (!(dienst.get("security_opt") instanceof List<?> opt)
                    || opt.stream().map(String::valueOf).noneMatch(o -> o.startsWith("no-new-privileges"))) {
                fehler.add(name + ": security_opt ohne no-new-privileges");
            }
            if (!UID.matcher(String.valueOf(dienst.get("user"))).matches()) {
                fehler.add(name + ": user nicht numerisch oder uid 0");
            }
            if (!Boolean.TRUE.equals(dienst.get("read_only"))) {
                fehler.add(name + ": read_only fehlt");
            }
            if (dienst.get("mem_limit") == null || dienst.get("cpus") == null) {
                fehler.add(name + ": mem_limit oder cpus fehlt");
            }
            for (String verboten : List.of("ports", "privileged", "cap_add", "network_mode", "pid", "ipc",
                    "devices", "env_file")) {
                if (dienst.containsKey(verboten)) {
                    fehler.add(name + ": " + verboten + " nicht erlaubt");
                }
            }
        });
        assertThat(fehler).isEmpty();
    }

    @Test
    void amNetzWebNurApiUndKeycloak() throws IOException {
        List<String> amWeb = new ArrayList<>();
        dienste().forEach((name, dienst) -> {
            if (dienst.get("networks") instanceof Map<?, ?> netze && netze.containsKey("web")
                    || dienst.get("networks") instanceof List<?> liste && liste.contains("web")) {
                amWeb.add(name);
            }
        });
        assertThat(amWeb).containsExactlyInAnyOrder("api", "kc");
        assertThat(karte(karte(yaml(COMPOSE).get("networks")).get("intern")).get("internal")).isEqualTo(true);
    }

    @Test
    void speicherbudgetDerStufeA() throws IOException {
        Map<String, Object> env = karte(yaml(WORKFLOW).get("env"));
        long budgetMib = Long.parseLong(String.valueOf(env.get("BUDGET_MEM_MIB")));
        BigDecimal budgetCpus = new BigDecimal(String.valueOf(env.get("BUDGET_CPUS")));
        long summeBytes = 0;
        BigDecimal summeCpus = BigDecimal.ZERO;
        for (Map<String, Object> dienst : dienste().values()) {
            summeBytes += bytes(String.valueOf(dienst.get("mem_limit")));
            summeCpus = summeCpus.add(new BigDecimal(String.valueOf(dienst.get("cpus"))));
        }
        assertThat(summeBytes).isLessThanOrEqualTo(budgetMib * 1024 * 1024);
        assertThat(summeCpus).isLessThanOrEqualTo(budgetCpus);
    }

    /** Schritte, die Dateien schreiben, setzen zuerst {@code umask 077} (kein Fenster mit 0644): nicht nur die mit
     *  Secrets aus {@code env:}, auch Status, Logs und Merkdateien vom Server (Review PR #42, Kann). */
    @Test
    void geheimnisseNurMitUmask077InDateien() throws IOException {
        List<String> fehler = new ArrayList<>();
        for (Object job : karte(yaml(WORKFLOW).get("jobs")).values()) {
            for (Object eintrag : (List<?>) karte(job).get("steps")) {
                Map<String, Object> schritt = karte(eintrag);
                String run = Objects.toString(schritt.get("run"), "");
                if (schreibt(run) && !ersteZeile(run).equals("umask 077")) {
                    fehler.add(String.valueOf(schritt.get("name")));
                }
            }
        }
        assertThat(fehler).as("Schritte ohne umask 077 am Anfang").isEmpty();
    }

    @Test
    void erkenntSchreibendeBefehle() {
        assertThat(List.of("ssh host status > status.txt", "x 2>fehler.log", "a | tee deploy.log", "cp a b",
                "mkdir -p bundle", "install -d -m 700 ~/.ssh", "docker save t -o x.tar", "curl -s -o seite.html u",
                "echo x >> \"$RUNNER_TEMP/merk\""))
                .allSatisfy(run -> assertThat(schreibt(run)).as(run).isTrue());
        assertThat(List.of("x 2>/dev/null", "x > /dev/null 2>&1", "echo a >> \"$GITHUB_OUTPUT\"",
                "echo a >> $GITHUB_STEP_SUMMARY", "curl -s -o /dev/null u", "grep -c x <<< \"$a\"",
                "rm -f status.txt", "[ \"$a\" -ne 0 ]"))
                .allSatisfy(run -> assertThat(schreibt(run)).as(run).isFalse());
    }

    private static boolean schreibt(String run) {
        return run.lines().map(String::strip).filter(z -> !z.startsWith("#")).anyMatch(z -> SCHREIBT.matcher(z).find());
    }

    private static String ersteZeile(String run) {
        return run.lines().map(String::strip).filter(z -> !z.isEmpty() && !z.startsWith("#"))
                .findFirst().orElse("");
    }

    private static long bytes(String wert) {
        var treffer = MEM.matcher(wert.toLowerCase(Locale.ROOT));
        assertThat(treffer.matches()).as("mem_limit %s", wert).isTrue();
        long zahl = Long.parseLong(treffer.group(1));
        return switch (treffer.group(2)) {
            case "k" -> zahl * 1024;
            case "m" -> zahl * 1024 * 1024;
            case "g" -> zahl * 1024 * 1024 * 1024;
            default -> zahl;
        };
    }

    private static Map<String, Map<String, Object>> dienste() throws IOException {
        Map<String, Object> roh = karte(yaml(COMPOSE).get("services"));
        assertThat(roh).isNotEmpty();
        Map<String, Map<String, Object>> dienste = new java.util.TreeMap<>();
        roh.forEach((name, dienst) -> dienste.put(name, karte(dienst)));
        return dienste;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> karte(Object wert) {
        assertThat(wert).isInstanceOf(Map.class);
        return (Map<String, Object>) wert;
    }

    private static Map<String, Object> yaml(Path datei) throws IOException {
        // «on:» bleibt mit SafeConstructor ein Boolean-Schlüssel; gelesen werden nur env und jobs
        return karte(new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(datei, StandardCharsets.UTF_8)));
    }
}
