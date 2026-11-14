package ch.urinet.archiv.gemeinsam;

import io.smallrye.config.SmallRyeConfig;
import io.vertx.ext.mail.mailencoder.EmailAddress;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mail-Versand und Alarm-Empfänger in prod (Aufgabe 5.6, Req 6.4, 16.5; Entscheid Fabian 14.11.2026): Versand über
 * Hostpoint mit dem Postfach {@code archiv@urinet.ch}, Passwort nur als Secret-Datei {@code archiv_smtp_passwort},
 * Empfänger aus der Umgebung {@code ARCHIV_ALARM_EMPFAENGER_URINET}, die der Deploy als {@code .env} neben
 * {@code compose.yml} legt. Geprüft wird der Weg {@code .env} → Interpolation in {@code compose.yml} → Umgebung der API →
 * {@code archiv.benachrichtigung.empfaenger.urinet}; ohne Wert bleibt es beim Log (MailInhaberBenachrichtigung). Dass
 * prod ohne Passwort und Empfänger startet, zeigt {@link SecretDateienTest}.
 */
class DeployBenachrichtigungTest {

    private static final Path COMPOSE = GemeinsamSchritte.REPO.resolve("deploy/infomaniak/compose.yml");
    private static final Path WORKFLOW = GemeinsamSchritte.REPO.resolve(".github/workflows/infomaniak-deploy.yml");
    private static final String VARIABLE = "ARCHIV_ALARM_EMPFAENGER_URINET";
    private static final String EMPFAENGER = "archiv.benachrichtigung.empfaenger.urinet";
    /** Interpolation von Compose, soweit compose.yml sie nutzt: {@code ${NAME:-vorgabe}}. */
    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([A-Z0-9_]+):-([^}]*)}");

    @Test
    void empfaengerKommtAusDerEnvDesBundles() throws IOException {
        SmallRyeConfig config = DeployStufeTest.prodConfig(umgebung(Map.of(VARIABLE, "inhaber@example.invalid")));

        assertThat(config.getOptionalValue(EMPFAENGER, String.class)).contains("inhaber@example.invalid");
    }

    @Test
    void ohneVariableBleibtEsBeimLog() throws IOException {
        SmallRyeConfig config = DeployStufeTest.prodConfig(umgebung(Map.of()));

        assertThat(config.getOptionalValue(EMPFAENGER, String.class)).isEmpty();
        assertThat(config.getOptionalValue("quarkus.mailer.password", String.class)).isEmpty();
    }

    @Test
    void versandUeberDasPostfachDesArchivs() throws IOException {
        SmallRyeConfig config = DeployStufeTest.prodConfig(umgebung(Map.of()));

        assertThat(config.getValue("quarkus.mailer.host", String.class)).isEqualTo("asmtp.mail.hostpoint.ch");
        assertThat(config.getValue("quarkus.mailer.port", Integer.class)).isEqualTo(587);
        assertThat(config.getValue("quarkus.mailer.start-tls", String.class)).isEqualTo("REQUIRED");
        assertThat(config.getValue("quarkus.mailer.login", String.class)).isEqualTo("REQUIRED");
        assertThat(config.getValue("quarkus.mailer.username", String.class)).isEqualTo("archiv@urinet.ch");
        // Vert.x nimmt für MAIL FROM nur die Adresse; Hostpoint verlangt dort das angemeldete Postfach
        var absender = new EmailAddress(config.getValue("quarkus.mailer.from", String.class));
        assertThat(absender.getEmail()).isEqualTo("archiv@urinet.ch");
        assertThat(absender.getName()).isEqualTo("UriNet Archiv");
    }

    @Test
    @SuppressWarnings("unchecked")
    void passwortAlsSecretDateiAusDemDeploy() throws IOException {
        Map<String, Object> compose = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(COMPOSE, StandardCharsets.UTF_8));
        var api = ((Map<String, Map<String, Object>>) compose.get("services")).get("api");
        assertThat((List<String>) api.get("secrets")).contains("archiv_smtp_passwort");
        assertThat(((Map<String, Map<String, String>>) compose.get("secrets")).get("archiv_smtp_passwort"))
                .containsEntry("file", "./secrets/archiv_smtp_passwort");

        String workflow = Files.readString(WORKFLOW, StandardCharsets.UTF_8);
        assertThat(workflow)
                .contains("ARCHIV_SMTP_PASSWORT: ${{ secrets.ARCHIV_SMTP_PASSWORT }}")
                .contains("schreibe archiv_smtp_passwort \"$ARCHIV_SMTP_PASSWORT\"")
                .contains(VARIABLE + ": ${{ vars." + VARIABLE + " }}")
                .contains("printf '" + VARIABLE + "=%s\\n' \"$" + VARIABLE + "\" > bundle/.env");
    }

    /** Umgebung der API, wie Compose sie mit der {@code .env} des Bundles interpoliert. */
    private static Map<String, String> umgebung(Map<String, String> env) throws IOException {
        Map<String, String> umgebung = new HashMap<>();
        DeployStufeTest.apiUmgebung().forEach((name, wert) -> {
            Matcher m = INTERPOLATION.matcher(wert);
            umgebung.put(name, m.replaceAll(r -> Matcher.quoteReplacement(
                    Optional.ofNullable(env.get(r.group(1))).filter(s -> !s.isEmpty()).orElse(r.group(2)))));
        });
        assertThat(umgebung).containsKey(VARIABLE);
        return umgebung;
    }
}
