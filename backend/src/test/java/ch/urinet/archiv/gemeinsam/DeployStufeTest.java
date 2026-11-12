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

    /** Alle Dienste mit dem Image der API, damit z. B. ein eigener Worker-Dienst (Stufe B) nicht durchrutscht. */
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

    /** Abweichungen als Schlüssel der Konfiguration; leer, wenn die Umgebung zur Stufe passt. */
    static List<String> pruefen(Map<String, String> umgebung) throws IOException {
        SmallRyeConfig config = new SmallRyeConfigBuilder().withProfile("prod").addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(DeployStufeTest.class.getClassLoader()
                        .getResource("application.properties")))
                .withSources(new EnvConfigSource(umgebung, 300) {
                })
                .build();
        var einstellungen = new Stufenregel.Ablageeinstellungen(
                config.getValue("archiv.ablage.manifest-sperrmodus", Sperrmodus.class),
                config.getOptionalValue("archiv.ablage.manifest-max-sperrfrist", Duration.class),
                config.getOptionalValue("archiv.ablage.max-sperrfrist", Duration.class),
                config.getValue("archiv.ablage.bucket-praefix", String.class));
        Stufe stufe = Stufe.aus(config.getOptionalValue("archiv.stufe", String.class));

        List<String> abweichungen = new ArrayList<>(Stufenregel.verstoesse(stufe, einstellungen));
        if (stufe == Stufe.A) {
            abweichungen.addAll(Stufenregel.warnungen(stufe, einstellungen));
            if (!hoechstensEinTag(einstellungen.manifestHoechstfrist())) {
                abweichungen.add("archiv.ablage.manifest-max-sperrfrist");
            }
        }
        return abweichungen;
    }

    private static boolean hoechstensEinTag(Optional<Duration> frist) {
        return frist.filter(f -> f.compareTo(Stufenregel.HOECHSTFRIST_STUFE_A) <= 0).isPresent();
    }

    private static Map<String, String> apiUmgebung() throws IOException {
        return archivDienste().get("api");
    }

    /** Umgebung je Dienst, der dasselbe Image wie {@code api} verwendet. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> archivDienste() throws IOException {
        Map<String, Object> compose = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(COMPOSE, StandardCharsets.UTF_8));
        var dienste = (Map<String, Map<String, Object>>) compose.get("services");
        Object image = dienste.get("api").get("image");
        Map<String, Map<String, String>> ergebnis = new TreeMap<>();
        dienste.forEach((name, dienst) -> {
            if (!image.equals(dienst.get("image"))) {
                return;
            }
            Object environment = dienst.get("environment");
            assertThat(environment).as("environment des Dienstes %s in compose.yml als Abbildung (NAME: wert), nicht"
                    + " als Liste", name).isInstanceOf(Map.class);
            Map<String, String> umgebung = new HashMap<>();
            ((Map<String, Object>) environment).forEach((schluessel, wert) -> umgebung.put(schluessel,
                    String.valueOf(wert)));
            ergebnis.put(name, umgebung);
        });
        return ergebnis;
    }
}
