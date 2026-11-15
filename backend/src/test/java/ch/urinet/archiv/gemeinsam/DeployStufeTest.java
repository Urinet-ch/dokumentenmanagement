package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.ablage.domain.Stufenregel;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

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
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

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
        return new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(datei, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> strings(Object environment) {
        Map<String, String> ergebnis = new HashMap<>();
        ((Map<String, Object>) environment).forEach((schluessel, wert) -> ergebnis.put(schluessel,
                String.valueOf(wert)));
        return ergebnis;
    }
}
