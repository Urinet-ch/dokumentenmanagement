package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Härtung des Deploys nach der Compose-Policy der VPS (tech.md «Plattform und Betrieb», Aufgabe 1.9, Review PR #42
 * S1 und S2). Die Policy des Infra-Repos prüft Limits, {@code cap_drop}, {@code no-new-privileges} und die uid nur für
 * Apps auf {@code -api}, nicht für {@code archiv}; darum prüft das Repo sie selbst – auch für die Restore-Probe
 * ({@code deploy/probe/compose.yml}) mit ihrem eigenständigen Dienst {@code worker} (Aufgabe 2.6, Review PR #266, M2).
 */
class DeployHaertungTest {

    private static final Path REPO = GemeinsamSchritte.REPO;
    private static final Path COMPOSE = REPO.resolve("deploy/infomaniak/compose.yml");
    private static final Path WORKFLOW = REPO.resolve(".github/workflows/infomaniak-deploy.yml");
    private static final Pattern UID = Pattern.compile("[1-9]\\d*(:\\d+)?");
    private static final Pattern MEM = Pattern.compile("(\\d+)([kmg]?)b?");
    private static final Pattern ROUTER_REGEL = Pattern.compile("traefik\\.http\\.routers\\.([^.]+)\\.rule");
    private static final Pattern HOST = Pattern.compile("Host\\(`([^`]*)`\\)");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*:-([^}]*)}");
    // Reserve für den noch fehlenden Scan-Eingang, Spec 09 E11.
    private static final long SFTP_RESERVE_BYTES = 96L * 1024 * 1024;
    private static final BigDecimal SFTP_RESERVE_CPUS = new BigDecimal("0.1");
    private static final long BUDGET_BYTES = 2144L * 1024 * 1024;
    private static final BigDecimal BUDGET_CPUS = new BigDecimal("1.6");
    /** Schreibt in eine Datei (nicht {@code >&}, nicht {@code /dev/null}). */
    /** Schreibt eine Datei: Umleitung (auch {@code 2>}) ausser nach /dev/null, auf einen Deskriptor oder in die
     *  Dateien von Actions ({@code $GITHUB_OUTPUT} u. a.), oder ein Befehl, der Dateien anlegt. */
    private static final Pattern SCHREIBT = Pattern.compile(
            "(?<![<>&])\\d?>{1,2}(?![>&])\\s*(?!/dev/null|\"?\\$\\{?GITHUB_(?:OUTPUT|ENV|PATH|STEP_SUMMARY)\\b)\\S"
                    + "|\\b(?:tee|cp|mv|mkdir|install|touch)\\s|\\bdocker\\s+save\\b"
                    + "|\\bcurl\\b[^\\n]*\\s-o\\s*(?!/dev/null)\\S");

    @ParameterizedTest
    @ValueSource(strings = {"deploy/infomaniak/compose.yml", "deploy/probe/compose.yml"})
    void jederDienstGehaertet(String datei) throws IOException {
        List<String> fehler = new ArrayList<>();
        dienste(REPO.resolve(datei)).forEach((name, dienst) -> {
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

    /** Auch der eigenständige Worker der Restore-Probe läuft nie als root (Aufgabe 2.6, Review PR #266, M2). */
    @ParameterizedTest
    @ValueSource(strings = {"deploy/infomaniak/compose.yml", "deploy/probe/compose.yml"})
    void workerDienstLaeuftMitNumerischerNichtRootUid(String datei) throws IOException {
        List<Map.Entry<String, Map<String, Object>>> worker = dienste(REPO.resolve(datei)).entrySet().stream()
                .filter(e -> hatWorkerRolle(e.getValue()))
                .toList();

        assertThat(worker).isNotEmpty().allSatisfy(e ->
                assertThat(String.valueOf(e.getValue().get("user"))).as(e.getKey()).matches(UID));
    }

    @Test
    void netzeUndSecretsNurAnVorgesehenenDiensten() throws IOException {
        Map<String, Map<String, Object>> dienste = dienste();
        assertThat(fehlerNetzeUndSecrets(dienste)).isEmpty();
        assertThat(karte(karte(yaml(COMPOSE).get("networks")).get("intern")).get("internal")).isEqualTo(true);
        assertThat(karte(karte(yaml(COMPOSE).get("networks")).get("web-archiv")).get("external")).isEqualTo(true);
        dienste.get("db").put("networks", Map.of("intern", Map.of(), "web-archiv", Map.of()));
        assertThat(fehlerNetzeUndSecrets(dienste)).contains("db: Netze");
        dienste.get("db").put("secrets", List.of("archiv_manifest_key"));
        assertThat(fehlerNetzeUndSecrets(dienste)).contains("db: Secrets");
        dienste.put("sftp", new TreeMap<>(Map.of(
                "networks", Map.of("intern", Map.of(), "web-archiv", Map.of()),
                "labels", Map.of("traefik.docker.network", "web-archiv"))));
        assertThat(fehlerNetzeUndSecrets(dienste)).doesNotContain("sftp: Netze");
        dienste.get("sftp").put("networks", Map.of("intern", Map.of(), "web", Map.of()));
        assertThat(fehlerNetzeUndSecrets(dienste)).contains("sftp: Netze");
        dienste.get("sftp").put("ports", List.of("2222:22"));
        assertThat(fehlerNetzeUndSecrets(dienste)).contains("sftp: Ports");
    }

    private static List<String> fehlerNetzeUndSecrets(Map<String, Map<String, Object>> dienste) {
        Map<String, Set<String>> secrets = Map.of(
                "api", Set.of("archiv_db_app", "archiv_db_owner", "s3_app_zugang_id",
                        "s3_app_zugang_geheimnis", "infomaniak_ai_token", "archiv_manifest_key",
                        "archiv_data_key", "archiv_smtp_passwort", "namenssperre_salz", "keycloak_verwaltung_secret"),
                "db", Set.of("archiv_db_postgres", "archiv_db_owner_scram", "archiv_db_app_scram", "keycloak_db_scram"),
                "kc", Set.of("keycloak_db", "keycloak_admin", "keycloak_smtp_passwort",
                        "google_client_id", "google_client_secret"));
        List<String> fehler = new ArrayList<>();
        dienste.forEach((name, dienst) -> {
            Set<String> erwartet = Set.of("api", "kc", "sftp").contains(name)
                    ? Set.of("intern", "web-archiv") : Set.of("intern");
            Object netze = dienst.get("networks");
            Set<?> angebunden = netze instanceof Map<?, ?> map ? map.keySet()
                    : netze instanceof List<?> liste ? Set.copyOf(liste) : Set.of();
            if (!angebunden.equals(erwartet)) {
                fehler.add(name + ": Netze");
            }
            if (dienst.containsKey("ports")) {
                fehler.add(name + ": Ports");
            }
            if (Set.of("api", "kc", "sftp").contains(name)
                    && !"web-archiv".equals(karte(dienst.get("labels")).get("traefik.docker.network"))) {
                fehler.add(name + ": Traefik-Netz");
            }
            if (!Set.copyOf((List<?>) dienst.getOrDefault("secrets", List.of()))
                    .equals(secrets.getOrDefault(name, Set.of()))) {
                fehler.add(name + ": Secrets");
            }
        });
        return fehler;
    }

    @Test
    void probeBleibtImAnteilDerAngehaltenenApi() throws IOException {
        Map<String, Map<String, Object>> probe = dienste(REPO.resolve("deploy/probe/compose.yml"));
        Map<String, Object> api = dienste().get("api");
        assertThat(probeImApiAnteil(probe, api)).isTrue();
        probe.get("worker").put("cpus", "0.61");
        assertThat(probeImApiAnteil(probe, api)).isFalse();
        probe.get("worker").put("cpus", "0.15");
        probe.get("worker").put("mem_limit", "1024m");
        assertThat(probeImApiAnteil(probe, api)).isFalse();
    }

    private static boolean probeImApiAnteil(Map<String, Map<String, Object>> probe, Map<String, Object> api) {
        long speicher = probe.values().stream().mapToLong(d -> bytes(String.valueOf(d.get("mem_limit")))).sum();
        BigDecimal cpus = probe.values().stream().map(d -> new BigDecimal(String.valueOf(d.get("cpus"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return speicher <= bytes(String.valueOf(api.get("mem_limit")))
                && cpus.compareTo(new BigDecimal(String.valueOf(api.get("cpus")))) <= 0;
    }

    @Test
    void speicherbudgetDerStufeA() throws IOException {
        Map<String, Object> env = karte(yaml(WORKFLOW).get("env"));
        long budgetMib = Long.parseLong(String.valueOf(env.get("BUDGET_MEM_MIB")));
        BigDecimal budgetCpus = new BigDecimal(String.valueOf(env.get("BUDGET_CPUS")));
        long summeBytes = 0;
        BigDecimal summeCpus = BigDecimal.ZERO;
        Map<String, Map<String, Object>> alleDienste = dienste();
        for (Map<String, Object> dienst : alleDienste.values()) {
            summeBytes += bytes(String.valueOf(dienst.get("mem_limit")));
            summeCpus = summeCpus.add(new BigDecimal(String.valueOf(dienst.get("cpus"))));
        }
        Map<String, Object> sftp = alleDienste.get("sftp");
        if (sftp != null) {
            summeBytes -= bytes(String.valueOf(sftp.get("mem_limit")));
            summeCpus = summeCpus.subtract(new BigDecimal(String.valueOf(sftp.get("cpus"))));
        }
        assertThat(summeBytes).isLessThanOrEqualTo(budgetMib * 1024 * 1024);
        assertThat(summeCpus).isLessThanOrEqualTo(budgetCpus);
        assertThat(imBudget(alleDienste)).isTrue();
    }

    @Test
    void budgetMitReserveOderSftp() throws IOException {
        Map<String, Map<String, Object>> dienste = dienste();
        dienste.remove("sftp");
        assertThat(imBudget(dienste)).isTrue();
        dienste.get("api").put("mem_limit", "1025m");
        assertThat(imBudget(dienste)).isFalse();
        dienste.get("api").put("mem_limit", "1024m");
        dienste.get("api").put("cpus", "0.61");
        assertThat(imBudget(dienste)).isFalse();
        dienste.get("api").put("cpus", "0.6");
        dienste.put("sftp", Map.of("mem_limit", "96m", "cpus", "0.1"));
        assertThat(imBudget(dienste)).isTrue();
        dienste.put("sftp", Map.of("mem_limit", "97m", "cpus", "0.1"));
        assertThat(imBudget(dienste)).isFalse();
        dienste.put("sftp", Map.of("mem_limit", "96m", "cpus", "0.11"));
        assertThat(imBudget(dienste)).isFalse();
    }

    private static boolean imBudget(Map<String, Map<String, Object>> dienste) {
        long summeBytes = dienste.containsKey("sftp") ? 0 : SFTP_RESERVE_BYTES;
        BigDecimal summeCpus = dienste.containsKey("sftp") ? BigDecimal.ZERO : SFTP_RESERVE_CPUS;
        for (Map<String, Object> dienst : dienste.values()) {
            long speicher = bytes(String.valueOf(dienst.get("mem_limit")));
            BigDecimal cpus = new BigDecimal(String.valueOf(dienst.get("cpus")));
            if (speicher <= 0 || cpus.signum() <= 0) {
                return false;
            }
            summeBytes += speicher;
            summeCpus = summeCpus.add(cpus);
        }
        return summeBytes <= BUDGET_BYTES && summeCpus.compareTo(BUDGET_CPUS) <= 0;
    }

    /** Die Compose-Policy der VPS lehnt {@code priority} überall ab (Deploy vom 18.11.2026, PR #299). */
    @Test
    void keineTraefikPrioritaet() throws IOException {
        List<String> prioritaet = new ArrayList<>();
        labels().forEach((dienst, labels) -> labels.keySet().stream()
                .filter(k -> k.startsWith("traefik.") && k.endsWith(".priority"))
                .forEach(k -> prioritaet.add(dienst + ": " + k)));
        assertThat(prioritaet).isEmpty();
    }

    /** HSTS an jedem Router (ZAP-Baseline gegen die VPS, Regel 10035, 26.11.2026) und jede Middleware am Dienst des
     *  Routers definiert: Traefik führt Router nur für gesunde Container. Hing {@code archiv-auth} an einer Middleware
     *  des Dienstes {@code api}, warteten beide Dienste aufeinander (Deploy 26.11.2026: 404 auf archiv und archiv-auth). */
    @Test
    void jederRouterSetztHstsMitEigenerMiddlewareDesDienstes() throws IOException {
        List<String> befunde = new ArrayList<>();
        labels().forEach((dienst, labels) -> labels.forEach((schluessel, wert) -> {
            Matcher router = Pattern.compile("traefik\\.http\\.routers\\.([^.]+)\\.middlewares").matcher(schluessel);
            if (!router.matches()) {
                return;
            }
            List<String> mittel = List.of(wert.replace(" ", "").split(","));
            // HSTS zuerst: eine Antwort der Drosselung (429) liefe sonst an der Header-Middleware vorbei (ZAP 27.11.2026)
            if (!mittel.get(0).endsWith("-sts")) {
                befunde.add(dienst + ": " + router.group(1) + " ohne HSTS an erster Stelle");
            }
            for (String m : mittel) {
                String praefix = "traefik.http.middlewares." + m + ".";
                if (labels.keySet().stream().noneMatch(k -> k.startsWith(praefix))) {
                    befunde.add(dienst + ": " + router.group(1) + " nutzt " + m + " ohne Definition am eigenen Dienst");
                }
            }
        }));
        assertThat(befunde).isEmpty();
        Map<String, String> alle = new java.util.TreeMap<>();
        labels().values().forEach(alle::putAll);
        for (String name : List.of("archiv-sts", "archiv-auth-sts")) {
            String basis = "traefik.http.middlewares." + name + ".headers.";
            assertThat(alle).containsEntry(basis + "stsincludesubdomains", "false");
            assertThat(alle).containsEntry(basis + "stspreload", "false");
            assertThat(alle).containsEntry(basis + "stsseconds", "31536000");
        }
    }

    /** Öffentlich nur archiv.… und archiv-auth.… (tech.md «Erreichbarkeit», ADR-010 P15); ein weiterer Host (z. B.
     *  VHF/GSK) braucht einen Entscheid von Fabian (Review PR #299, M1). Standardwerte von {@code ${…:-…}} gelten. */
    @Test
    void nurDieZweiOeffentlichenHosts() throws IOException {
        TreeSet<String> hosts = new TreeSet<>();
        routerRegeln().values().forEach(regel -> {
            Matcher host = HOST.matcher(regel);
            while (host.find()) {
                hosts.add(host.group(1).toLowerCase(Locale.ROOT));
            }
        });
        assertThat(hosts).containsExactly("archiv-auth.apps.urinet.ch", "archiv.apps.urinet.ch");
    }

    /** Ohne {@code priority} entschiede bei Überschneidung die Regellänge: Jede Anfrage trifft genau den erwarteten
     *  Router oder keinen (Spec 03, design.md 11.6; Review PR #299, S2). «-» heisst kein Router (404 von Traefik). */
    @ParameterizedTest(name = "{0}{1} → {2}")
    @CsvSource({
            "archiv.apps.urinet.ch, /public/v1/suche, archiv-public-suche",
            "archiv.apps.urinet.ch, /public/v1/suche/, archiv-public-suche",
            "archiv.apps.urinet.ch, /public/v1/dokumente, archiv-public",
            "archiv.apps.urinet.ch, /public/v1/erfassung, archiv-public",
            "archiv.apps.urinet.ch, /public/v1/suchen, archiv-public",
            "archiv.apps.urinet.ch, /public, archiv",
            "archiv.apps.urinet.ch, /, archiv",
            "archiv.apps.urinet.ch, /eingangskorb, archiv",
            "archiv.apps.urinet.ch, /api/v1/dokumente, archiv-api",
            "archiv.apps.urinet.ch, /q/health/live, archiv-health",
            "archiv.apps.urinet.ch, /q/health/ready, -",
            "archiv.apps.urinet.ch, /q/metrics, -",
            "ARCHIV.apps.urinet.ch, /api/v1/x, archiv-api",
            "archiv-auth.apps.urinet.ch, /realms/urinet-archiv/protocol/openid-connect/auth, archiv-auth",
            "archiv-auth.apps.urinet.ch, /resources/x/login.css, archiv-auth",
            "archiv-auth.apps.urinet.ch, /realms/master/, -",
            "archiv-auth.apps.urinet.ch, /admin/, -",
            "archiv-vhf-gsk.apps.urinet.ch, /, -",
            "archiv-vhf-gsk.apps.urinet.ch, /api/v1/branding, -"
    })
    void jedeAnfrageTrifftHoechstensEinenRouter(String host, String pfad, String erwartet) throws IOException {
        List<String> treffer = new ArrayList<>();
        routerRegeln().forEach((router, regel) -> {
            if (new Regel(regel, host, pfad).auswerten()) {
                treffer.add(router);
            }
        });
        assertThat(treffer).isEqualTo("-".equals(erwartet) ? List.of() : List.of(erwartet));
    }

    @Test
    void regelAuswerterKenntUndUndOderNicht() {
        String regel = "Host(`a.b`) && (Path(`/x`) || PathPrefix(`/y/`)) && !Path(`/y/z`)";
        assertThat(new Regel(regel, "a.b", "/x").auswerten()).isTrue();
        assertThat(new Regel(regel, "a.b", "/y/q").auswerten()).isTrue();
        assertThat(new Regel(regel, "a.b", "/y/z").auswerten()).isFalse();
        assertThat(new Regel(regel, "c.b", "/x").auswerten()).isFalse();
        assertThat(new Regel("Host(`a`) || Host(`b`) && Path(`/p`)", "a", "/q").auswerten()).isTrue();
        assertThatThrownBy(() -> new Regel("Method(`GET`)", "a", "/").auswerten())
                .isInstanceOf(IllegalArgumentException.class);
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

    private static Map<String, Map<String, String>> labels() throws IOException {
        Map<String, Map<String, String>> labels = new TreeMap<>();
        dienste().forEach((name, dienst) -> {
            Map<String, String> eigene = new TreeMap<>();
            if (dienst.get("labels") instanceof Map<?, ?> karte) {
                karte.forEach((k, v) -> eigene.put(String.valueOf(k), String.valueOf(v)));
            }
            labels.put(name, eigene);
        });
        return labels;
    }

    private static Map<String, String> routerRegeln() throws IOException {
        Map<String, String> regeln = new TreeMap<>();
        labels().values().forEach(l -> l.forEach((k, v) -> {
            Matcher router = ROUTER_REGEL.matcher(k);
            if (router.matches()) {
                regeln.put(router.group(1), VARIABLE.matcher(v).replaceAll(m -> Matcher.quoteReplacement(m.group(1))));
            }
        }));
        assertThat(regeln).isNotEmpty();
        return regeln;
    }

    /** Auswerter für den Teil der Traefik-Regelsprache, den compose.yml nutzt: {@code Host}, {@code Path},
     *  {@code PathPrefix}, {@code !}, {@code &&}, {@code ||} und Klammern ({@code &&} bindet stärker). Andere Matcher
     *  brechen ab, damit der Test sie nicht still falsch auswertet. */
    private static final class Regel {
        private final String text;
        private final String host;
        private final String pfad;
        private int pos;

        Regel(String text, String host, String pfad) {
            this.text = text;
            this.host = host;
            this.pfad = pfad;
        }

        boolean auswerten() {
            boolean wert = oder();
            leer();
            if (pos != text.length()) {
                throw new IllegalArgumentException("Regel nicht ausgewertet ab " + pos + ": " + text);
            }
            return wert;
        }

        private boolean oder() {
            boolean wert = und();
            while (nimm("||")) {
                wert |= und();
            }
            return wert;
        }

        private boolean und() {
            boolean wert = faktor();
            while (nimm("&&")) {
                wert &= faktor();
            }
            return wert;
        }

        private boolean faktor() {
            if (nimm("!")) {
                return !faktor();
            }
            if (nimm("(")) {
                boolean wert = oder();
                verlange(")");
                return wert;
            }
            leer();
            int start = pos;
            while (pos < text.length() && Character.isLetter(text.charAt(pos))) {
                pos++;
            }
            String matcher = text.substring(start, pos);
            verlange("(`");
            int ende = text.indexOf('`', pos);
            String wert = text.substring(pos, ende);
            pos = ende + 1;
            verlange(")");
            return switch (matcher) {
                case "Host" -> host.equalsIgnoreCase(wert);
                case "Path" -> pfad.equals(wert);
                case "PathPrefix" -> pfad.startsWith(wert);
                default -> throw new IllegalArgumentException("Matcher nicht unterstützt: " + matcher);
            };
        }

        private boolean nimm(String zeichen) {
            leer();
            if (text.startsWith(zeichen, pos)) {
                pos += zeichen.length();
                return true;
            }
            return false;
        }

        private void verlange(String zeichen) {
            if (!nimm(zeichen)) {
                throw new IllegalArgumentException("«" + zeichen + "» erwartet an " + pos + ": " + text);
            }
        }

        private void leer() {
            while (pos < text.length() && text.charAt(pos) == ' ') {
                pos++;
            }
        }
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
        return dienste(COMPOSE);
    }

    private static Map<String, Map<String, Object>> dienste(Path compose) throws IOException {
        Map<String, Object> roh = karte(yaml(compose).get("services"));
        assertThat(roh).isNotEmpty();
        Map<String, Map<String, Object>> dienste = new TreeMap<>();
        roh.forEach((name, dienst) -> dienste.put(name, karte(dienst)));
        return dienste;
    }

    private static boolean hatWorkerRolle(Map<String, Object> dienst) {
        Object environment = dienst.get("environment");
        Object rolle = environment instanceof Map<?, ?> werte ? werte.get("ARCHIV_ROLLE") : null;
        return rolle != null && Arrays.stream(String.valueOf(rolle).split(","))
                .map(String::strip).anyMatch("worker"::equals);
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
