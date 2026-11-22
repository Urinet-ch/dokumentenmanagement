package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.ablage.domain.Stufenregel;
import ch.urinet.archiv.sicherheit.adapter.in.start.SecretPruefungTestzugang;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stufe des Deploys (Req 6.11; erster Deploy 12.11.2026, fehlendes {@code ARCHIV_STUFE}, PR #73): Die Umgebung der API
 * in {@code deploy/infomaniak/compose.yml} zusammen mit {@code application.properties} (Profil {@code prod}) muss die
 * Startprüfung {@code AblageStufenPruefung} bestehen, schon in der CI statt erst beim Start auf der VPS. Ohne
 * {@code ARCHIV_STUFE} gelten die Regeln der Stufe B. Für Stufe A zusätzlich die Höchstfristen nach Req 6.11: Dokumente
 * unter {@code archiv/} und Manifeste höchstens 1 Tag, Präfix {@code urinet-archiv-a-}, damit die Test-Buckets beim
 * Wechsel (Aufgabe 5.11) löschbar sind.
 */
class DeployStufeTest {

    private static final Path COMPOSE = GemeinsamSchritte.REPO.resolve("deploy/infomaniak/compose.yml");
    private static final Path PROBE = GemeinsamSchritte.REPO.resolve("deploy/probe/compose.yml");
    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-(.*?))?}");

    @TempDir
    Path temporaer;

    @Test
    void composeDecktDiePflichtGeheimnisse() throws IOException {
        Map<String, Object> compose = compose(COMPOSE);
        for (var dienst : archivDienste().entrySet()) {
            for (Map<String, String> umgebung : composeVarianten(dienst.getValue())) {
                if (dienst.getKey().equals("api")) {
                    assertThat(prodConfig(umgebung).getValues("archiv.rolle", String.class))
                            .as("Rolle von Dienst api in %s", COMPOSE).contains("api");
                }
                assertThatCode(() -> secretsPruefen(compose, dienst.getKey(), umgebung))
                        .as("Pflicht-Geheimnisse: %s / Dienst %s", COMPOSE, dienst.getKey())
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void fehlendeDateiUndUebersteuerterZugangWerdenErkannt() throws IOException {
        Map<String, Object> compose = compose(COMPOSE);
        Map<String, Object> api = composeDienste(compose).get("api");
        @SuppressWarnings("unchecked")
        List<Object> secrets = (List<Object>) api.get("secrets");
        assertThat(secrets.remove("archiv_db_app")).isTrue();
        assertThatThrownBy(() -> secretsPruefen(compose, "api", apiUmgebung()))
                .hasMessageContaining("archiv_db_app");

        secrets.add("archiv_db_app");
        Map<String, String> umgebung = apiUmgebung();
        umgebung.put("QUARKUS_DATASOURCE_PASSWORD", "synthetisch");
        assertThatThrownBy(() -> secretsPruefen(compose, "api", umgebung))
                .hasMessageContaining("archiv_db_app").hasMessageNotContaining("synthetisch");

        umgebung.remove("QUARKUS_DATASOURCE_PASSWORD");
        umgebung.put("ARCHIV_SECRETS_PFLICHT", "archiv_db_app");
        assertThatThrownBy(() -> secretsPruefen(compose, "api", umgebung))
                .hasMessageContaining("archiv.secrets.pflicht");
    }

    @Test
    void adminAdresseAktiviertDieBedingteSecretPruefung() throws IOException {
        Map<String, Object> compose = compose(COMPOSE);
        @SuppressWarnings("unchecked")
        List<Object> secrets = (List<Object>) composeDienste(compose).get("api").get("secrets");
        secrets.removeIf(eintrag -> "keycloak_verwaltung_secret".equals(eintrag)
                || eintrag instanceof Map<?, ?> lang
                && ("keycloak_verwaltung_secret".equals(lang.get("target"))
                || !lang.containsKey("target") && "keycloak_verwaltung_secret".equals(lang.get("source"))));
        Map<String, String> umgebung = apiUmgebung();
        umgebung.put("ARCHIV_KEYCLOAK_ADMIN_URL", "https://keycloak.invalid");
        for (String rolle : List.of("api", "kommando")) {
            umgebung.put("ARCHIV_ROLLE", rolle);
            assertThatThrownBy(() -> secretsPruefen(compose, "api", umgebung))
                    .as("Rolle %s", rolle).hasMessageContaining("keycloak_verwaltung_secret");
        }
        umgebung.put("ARCHIV_ROLLE", "worker");
        assertThatCode(() -> secretsPruefen(compose, "api", umgebung)).doesNotThrowAnyException();
    }

    @Test
    void migrationsSecretIstNurBeiAktiverMigrationPflicht() throws IOException {
        Map<String, Object> compose = compose(COMPOSE);
        @SuppressWarnings("unchecked")
        List<Object> secrets = (List<Object>) composeDienste(compose).get("api").get("secrets");
        assertThat(secrets.remove("archiv_db_owner")).isTrue();
        Map<String, String> umgebung = apiUmgebung();
        umgebung.put("ARCHIV_ROLLE", "worker");
        umgebung.put("QUARKUS_LIQUIBASE_MIGRATE_AT_START", "false");
        assertThatCode(() -> secretsPruefen(compose, "api", umgebung)).doesNotThrowAnyException();
        umgebung.put("ARCHIV_ROLLE", "api");
        umgebung.put("QUARKUS_LIQUIBASE_MIGRATE_AT_START", "true");
        assertThatThrownBy(() -> secretsPruefen(compose, "api", umgebung))
                .hasMessageContaining("archiv_db_owner");
    }

    @Test
    void umbenannteSecretDateiUndVolumeWerdenAlsZieleGeprueft() throws IOException {
        Map<String, Object> compose = compose(COMPOSE);
        Map<String, Object> api = composeDienste(compose).get("api");
        @SuppressWarnings("unchecked")
        List<Object> secrets = (List<Object>) api.get("secrets");
        @SuppressWarnings("unchecked")
        List<Object> volumes = (List<Object>) api.get("volumes");
        @SuppressWarnings("unchecked")
        Map<String, Object> deklariert = (Map<String, Object>) compose.get("secrets");
        assertThat(secrets.remove("archiv_db_app")).isTrue();
        deklariert.put("andere_quelle", Map.of("file", "./secrets/andere_quelle"));
        secrets.add(Map.of("source", "andere_quelle", "target", "archiv_db_app"));
        assertThat(secrets.remove("s3_app_zugang_id")).isTrue();
        volumes.add("./synthetisch:/run/secrets/s3_app_zugang_id:ro");
        Map<String, String> umgebung = apiUmgebung();
        umgebung.put("ARCHIV_ROLLE", "worker");
        assertThatCode(() -> secretsPruefen(compose, "api", umgebung)).doesNotThrowAnyException();
        volumes.removeLast();
        assertThatThrownBy(() -> secretsPruefen(compose, "api", umgebung))
                .hasMessageContaining("s3_app_zugang_id");
    }

    private void secretsPruefen(Map<String, Object> compose, String dienstName,
                                Map<String, String> umgebung) throws IOException {
        Map<String, Object> dienst = composeDienste(compose).get(dienstName);
        assertThat(dienst).as("Dienst %s in %s", dienstName, COMPOSE).doesNotContainKey("env_file");
        Path dateien = Files.createTempDirectory(temporaer, dienstName);
        Map<?, ?> deklarierteSecrets = (Map<?, ?>) compose.get("secrets");
        Object secrets = dienst.getOrDefault("secrets", List.of());
        assertThat(secrets).as("secrets von Dienst %s", dienstName).isInstanceOf(List.class);
        for (Object eintrag : (List<?>) secrets) {
            String quelle;
            String ziel;
            if (eintrag instanceof String name) {
                quelle = name;
                ziel = name;
            } else if (eintrag instanceof Map<?, ?> lang) {
                quelle = String.valueOf(lang.get("source"));
                ziel = String.valueOf(lang.containsKey("target") ? lang.get("target") : quelle);
            } else {
                throw new IllegalArgumentException("Unbekannter Secret-Eintrag in Dienst " + dienstName);
            }
            assertThat(deklarierteSecrets.containsKey(quelle)).as("Secret-Quelle %s von Dienst %s",
                    quelle, dienstName).isTrue();
            if (!ziel.startsWith("/") || ziel.startsWith("/run/secrets/")) {
                synthetischeDatei(dateien, ziel);
            }
        }
        Object volumes = dienst.getOrDefault("volumes", List.of());
        assertThat(volumes).as("volumes von Dienst %s", dienstName).isInstanceOf(List.class);
        for (Object eintrag : (List<?>) volumes) {
            String ziel;
            if (eintrag instanceof String kurz) {
                String[] teile = kurz.split(":", -1);
                ziel = teile.length > 1 ? teile[1] : teile[0];
            } else if (eintrag instanceof Map<?, ?> lang) {
                ziel = String.valueOf(lang.get("target"));
            } else {
                throw new IllegalArgumentException("Unbekannter Volume-Eintrag in Dienst " + dienstName);
            }
            if (ziel.equals("/run/secrets") || ziel.equals("/run/secrets/")) {
                throw new IllegalArgumentException("Secret-Verzeichnis als Volume in Dienst " + dienstName
                        + " kann nicht aus Compose bestimmt werden");
            }
            if (ziel.startsWith("/run/secrets/")) {
                synthetischeDatei(dateien, ziel);
            }
        }
        SmallRyeConfig config = SecretPruefungTestzugang.config(umgebung, dateien);
        assertThat(config.getValue("smallrye.config.source.file.locations", String.class))
                .as("Secret-Verzeichnis von Dienst %s", dienstName).isEqualTo("/run/secrets");
        SmallRyeConfig standard = prodConfig(Map.of());
        for (String schluessel : List.of("archiv.secrets.pflicht", "archiv.secrets.pflicht-migration")) {
            assertThat(config.getOptionalValues(schluessel, String.class).orElse(List.of()))
                    .as("%s für Dienst %s", schluessel, dienstName)
                    .containsAll(standard.getValues(schluessel, String.class));
        }
        config.getOptionalValue("quarkus.datasource.password", String.class);
    }

    private static List<Map<String, String>> composeVarianten(Map<String, String> umgebung) {
        Set<String> variablen = new TreeSet<>();
        umgebung.values().forEach(wert -> {
            Matcher treffer = INTERPOLATION.matcher(wert);
            while (treffer.find()) {
                variablen.add(treffer.group(1));
            }
        });
        List<Map<String, String>> eingaben = new ArrayList<>();
        eingaben.add(Map.of());
        variablen.forEach(name -> eingaben.add(Map.of(name, "https://beispiel.invalid")));
        Map<String, String> alle = new HashMap<>();
        variablen.forEach(name -> alle.put(name, "https://beispiel.invalid"));
        eingaben.add(alle);
        return eingaben.stream().map(eingabe -> {
            Map<String, String> ergebnis = new HashMap<>();
            umgebung.forEach((name, wert) -> ergebnis.put(name, composeWert(wert, eingabe)));
            return ergebnis;
        }).toList();
    }

    private static String composeWert(String wert, Map<String, String> eingabe) {
        Matcher treffer = INTERPOLATION.matcher(wert);
        StringBuffer ergebnis = new StringBuffer();
        while (treffer.find()) {
            String standard = treffer.group(2) == null ? "" : treffer.group(2);
            treffer.appendReplacement(ergebnis, Matcher.quoteReplacement(eingabe.getOrDefault(treffer.group(1), standard)));
        }
        treffer.appendTail(ergebnis);
        if (ergebnis.indexOf("${") >= 0) {
            throw new IllegalArgumentException("Compose-Interpolation kann nicht geprüft werden: " + wert);
        }
        return ergebnis.toString();
    }

    private static void synthetischeDatei(Path verzeichnis, String ziel) throws IOException {
        String name = ziel.startsWith("/run/secrets/") ? ziel.substring("/run/secrets/".length()) : ziel;
        if (name.contains("/") || name.isBlank() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Ungültiges Secret-Ziel: " + ziel);
        }
        Files.writeString(verzeichnis.resolve(name), "synthetischer-platzhalter");
    }

    /** Alle Dienste des Archivs (siehe {@link #archivDienste()}), damit ein eigener Worker-Dienst nicht durchrutscht. */
    @Test
    void umgebungDerApiPasstZurStufe() throws IOException {
        Map<String, Map<String, String>> dienste = archivDienste();
        assertThat(dienste).containsKey("api");
        for (var dienst : dienste.entrySet()) {
            assertThat(pruefen(dienst.getValue())).as("Dienst %s", dienst.getKey()).isEmpty();
        }
    }

    @Test
    void ohneArchivStufeWirdDiePruefungRot() throws IOException {
        Map<String, String> umgebung = apiUmgebung();
        assertThat(umgebung).containsKey("ARCHIV_STUFE");
        umgebung.remove("ARCHIV_STUFE");

        assertThat(pruefen(umgebung)).contains("archiv.ablage.bucket-praefix", "archiv.ablage.max-sperrfrist");
    }

    @Test
    void stufeAMitZuLangerOderFehlenderHoechstfristWirdRot() throws IOException {
        Map<String, String> zuLang = apiUmgebung();
        zuLang.put("ARCHIV_ABLAGE_MAX_SPERRFRIST", "P2D");
        assertThat(pruefen(zuLang)).containsExactly("archiv.ablage.max-sperrfrist");

        Map<String, String> ohneManifestFrist = apiUmgebung();
        ohneManifestFrist.remove("ARCHIV_ABLAGE_MANIFEST_MAX_SPERRFRIST");
        assertThat(pruefen(ohneManifestFrist)).containsExactly("archiv.ablage.manifest-max-sperrfrist");
    }

    @Test
    void stufePIstInAllenInfomaniakComposeDateienVerboten() throws IOException {
        List<Path> composeDateien;
        try (Stream<Path> dateien = Files.walk(GemeinsamSchritte.REPO.resolve("deploy/infomaniak"))) {
            composeDateien = dateien.filter(Files::isRegularFile)
                    .filter(datei -> datei.getFileName().toString().startsWith("compose"))
                    .filter(datei -> datei.getFileName().toString().endsWith(".yml"))
                    .toList();
        }
        assertThat(composeDateien).isNotEmpty();
        for (Path composeDatei : composeDateien) {
            for (var dienst : archivDienste(composeDatei).entrySet()) {
                Map<String, String> umgebung = new HashMap<>(dienst.getValue());
                umgebung.put("ARCHIV_STUFE", "P");
                umgebung.put("ARCHIV_ABLAGE_BUCKET_PRAEFIX", Stufenregel.PRAEFIX_STUFE_P);
                umgebung.put("ARCHIV_ABLAGE_MAX_SPERRFRIST", "P1D");
                umgebung.put("ARCHIV_ABLAGE_MANIFEST_MAX_SPERRFRIST", "P1D");
                assertThat(pruefen(umgebung, false)).as("%s / %s", composeDatei, dienst.getKey())
                        .contains("archiv.stufe");
            }
        }
    }

    @Test
    void probeComposeIstAbgeschottetUndErfuelltDieStufenregel() throws IOException {
        Map<String, Object> compose = compose(PROBE);
        Map<String, Map<String, Object>> alleDienste = composeDienste(compose);
        Map<String, Map<String, String>> dienste = archivDienste(PROBE);

        assertThat(alleDienste.keySet()).contains("api", "worker", "db", "kc");
        for (var dienst : alleDienste.entrySet()) {
            assertThat(dienst.getValue()).as("Dienst %s", dienst.getKey())
                    .doesNotContainKeys("labels", "ports");
        }
        for (var dienst : dienste.entrySet()) {
            Map<String, String> umgebung = dienst.getValue();
            assertThat(pruefen(umgebung, true)).as("Dienst %s", dienst.getKey()).isEmpty();
            assertThat(umgebung).containsEntry("ARCHIV_STUFE", "P")
                    .containsEntry("ARCHIV_ABLAGE_BUCKET_PRAEFIX", Stufenregel.PRAEFIX_STUFE_P)
                    .containsEntry("ARCHIV_ABLAGE_MAX_SPERRFRIST", "P1D")
                    .containsEntry("ARCHIV_ABLAGE_MANIFEST_MAX_SPERRFRIST", "P1D")
                    .containsEntry("ARCHIV_ABLAGE_MANIFEST_SPERRMODUS", "COMPLIANCE")
                    .containsEntry("ARCHIV_TSA_DIENSTE", "")
                    .containsEntry("ARCHIV_MANIFEST_BEIM_START", "false")
                    .containsEntry("ARCHIV_MANIFEST_ZEITPLAN", "off")
                    .containsEntry("ARCHIV_SECRETS_PFLICHT",
                            "archiv_db_app,s3_app_zugang_id,s3_app_zugang_geheimnis")
                    .containsEntry("ARCHIV_ALARM_EMPFAENGER_URINET", "");
            assertThat(alleDienste.get(dienst.getKey()).get("secrets").toString())
                    .doesNotContain("infomaniak_ai_token", "archiv_smtp_passwort", "archiv_manifest_key");
        }
        assertThat(dienste.get("api").toString())
                .contains("QUARKUS_OIDC_AUTH_SERVER_URL", "probe-kc:8080")
                .contains("ARCHIV_ALARM_EMPFAENGER_URINET");
        assertThat(alleDienste.get("kc").get("environment").toString()).contains("KC_HOSTNAME")
                .contains("probe-kc:8080");

        Map<?, ?> netzwerke = (Map<?, ?>) compose.get("networks");
        assertThat(netzwerke).hasSize(1);
        assertThat(((Map<?, ?>) netzwerke.get("intern")).get("internal")).isEqualTo(true);
        assertThat(compose).doesNotContainKey("ports");
    }

    /** Req 15.3, 15.11: zwei Zeitstempeldienste, je mit Endpunkt, Policy-OIDs, CA im Classpath und Prüfdaten-Adresse. */
    @Test
    void dieApiHatZweiVollstaendigKonfigurierteZeitstempeldienste() throws IOException {
        SmallRyeConfig config = prodConfig(apiUmgebung());
        List<String> dienste = config.getValues("archiv.tsa.dienste", String.class);

        assertThat(dienste).hasSize(2).doesNotHaveDuplicates();
        for (String dienst : dienste) {
            String p = "archiv.tsa.dienst." + dienst + ".";
            assertThat(config.getOptionalValue(p + "url", String.class)).as(p + "url").isPresent();
            assertThat(config.getOptionalValues(p + "policies", String.class)).as(p + "policies").isPresent();
            String ca = config.getValue(p + "ca", String.class);
            assertThat(DeployStufeTest.class.getClassLoader().getResource(ca)).as(p + "ca").isNotNull();
            assertThat(config.getOptionalValue(p + "ocsp-url", String.class)
                    .or(() -> config.getOptionalValue(p + "crl-url", String.class))).as(p + "ocsp-url/crl-url")
                    .isPresent();
        }
    }

    /** Prod-Befund 14.11.2026: sgpki stempelt mit Policy .2.10 (neues TSA-Zertifikat), freetsa-CRL erscheint jährlich. */
    @Test
    void zeitstempeldiensteKennenDiePolicyUndDasHoechstalterAusDerProduktion() throws IOException {
        SmallRyeConfig config = prodConfig(apiUmgebung());

        assertThat(config.getValues("archiv.tsa.dienst.sgpki.policies", String.class))
                .contains("2.16.756.1.17.3.5.2.4", "2.16.756.1.17.3.5.2.10");
        assertThat(config.getValues("archiv.tsa.dienst.freetsa.policies", String.class))
                .containsExactly("1.2.3.4.1");
        assertThat(config.getValue("archiv.tsa.dienst.freetsa.pruefdaten-hoechstalter", Duration.class))
                .isGreaterThan(Duration.ofDays(365));
        assertThat(config.getOptionalValue("archiv.tsa.dienst.sgpki.pruefdaten-hoechstalter", Duration.class))
                .isEmpty();
        assertThat(config.getValue("archiv.tsa.pruefdaten-hoechstalter", Duration.class))
                .isEqualTo(Duration.ofDays(7));
    }

    /** Abweichungen als Schlüssel der Konfiguration; leer, wenn die Umgebung zur Stufe passt. */
    static List<String> pruefen(Map<String, String> umgebung) throws IOException {
        return pruefen(umgebung, false);
    }

    private static List<String> pruefen(Map<String, String> umgebung, boolean probe) throws IOException {
        SmallRyeConfig config = prodConfig(umgebung);
        var einstellungen = new Stufenregel.Ablageeinstellungen(
                config.getValue("archiv.ablage.manifest-sperrmodus", Sperrmodus.class),
                config.getOptionalValue("archiv.ablage.manifest-max-sperrfrist", Duration.class),
                config.getOptionalValue("archiv.ablage.max-sperrfrist", Duration.class),
                config.getValue("archiv.ablage.bucket-praefix", String.class));
        Stufe stufe = Stufe.aus(config.getOptionalValue("archiv.stufe", String.class));

        List<String> abweichungen = new ArrayList<>(Stufenregel.verstoesse(stufe, einstellungen));
        if (stufe == Stufe.P && !probe) {
            abweichungen.add("archiv.stufe");
        }
        if (stufe == Stufe.A) {
            abweichungen.addAll(Stufenregel.warnungen(stufe, einstellungen));
            if (!hoechstensEinTag(einstellungen.manifestHoechstfrist())) {
                abweichungen.add("archiv.ablage.manifest-max-sperrfrist");
            }
        }
        return abweichungen;
    }

    static SmallRyeConfig prodConfig(Map<String, String> umgebung) throws IOException {
        return new SmallRyeConfigBuilder().withProfile("prod").addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(DeployStufeTest.class.getClassLoader()
                        .getResource("application.properties")))
                .withSources(new EnvConfigSource(umgebung, 300) {
                })
                .build();
    }

    private static boolean hoechstensEinTag(Optional<Duration> frist) {
        return frist.filter(f -> f.compareTo(Stufenregel.HOECHSTFRIST_STUFE_A) <= 0).isPresent();
    }

    static Map<String, String> apiUmgebung() throws IOException {
        return archivDienste().get("api");
    }

    /**
     * Umgebung je Dienst des Archivs: dasselbe Image wie {@code api}, Image {@code archiv-worker:…} oder
     * {@code ARCHIV_ROLLE} in der Umgebung (Review PR #74, K6: ein eigener Worker-Dienst auf Stufe B hat ein eigenes
     * Image).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> archivDienste() throws IOException {
        return archivDienste(COMPOSE);
    }

    private static Map<String, Map<String, String>> archivDienste(Path datei) throws IOException {
        var dienste = composeDienste(compose(datei));
        Object image = dienste.get("api").get("image");
        Map<String, Map<String, String>> ergebnis = new TreeMap<>();
        dienste.forEach((name, dienst) -> {
            Object environment = dienst.get("environment");
            boolean mitRolle = environment instanceof Map<?, ?> abbildung && abbildung.containsKey("ARCHIV_ROLLE")
                    || environment instanceof List<?> liste && liste.stream()
                            .anyMatch(eintrag -> String.valueOf(eintrag).startsWith("ARCHIV_ROLLE="));
            if (!image.equals(dienst.get("image")) && !String.valueOf(dienst.get("image")).startsWith("archiv-worker")
                    && !mitRolle) {
                return;
            }
            assertThat(environment).as("environment des Dienstes %s in compose.yml als Abbildung (NAME: wert), nicht"
                    + " als Liste", name).isInstanceOf(Map.class);
            ergebnis.put(name, strings(environment));
        });
        return ergebnis;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> composeDienste(Map<String, Object> compose) {
        return (Map<String, Map<String, Object>>) compose.get("services");
    }

    private static Map<String, Object> compose(Path datei) throws IOException {
        SafeConstructor constructor = new SafeConstructor(new LoaderOptions()) {
            {
                yamlConstructors.put(new Tag("!reset"), new AbstractConstruct() {
                    @Override
                    public Object construct(Node node) {
                        return "";
                    }
                });
            }
        };
        return new Yaml(constructor)
                .load(Files.readString(datei, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> strings(Object environment) {
        Map<String, String> ergebnis = new HashMap<>();
        ((Map<String, Object>) environment).forEach((schluessel, wert) -> {
            assertThat(wert).as("Compose-Umgebungswert %s ohne Standard", schluessel).isNotNull();
            ergebnis.put(schluessel, String.valueOf(wert));
        });
        return ergebnis;
    }
}
