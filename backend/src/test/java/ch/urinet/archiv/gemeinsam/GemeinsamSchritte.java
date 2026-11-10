package ch.urinet.archiv.gemeinsam;

import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Schritte für Rauchtest und CI-Regeln (features/gemeinsam). */
public class GemeinsamSchritte {

    /** Tests laufen in {@code backend/}; das Repository liegt eine Ebene höher. */
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    /**
     * Workflows, die Secrets brauchen dürfen, mit den erlaubten Namen (Req 16.7, 16.9): nur der Deploy (Aufgabe 1.9).
     * Er schreibt die Werte als Dateien ins Bundle für den Server und verarbeitet keine Daten.
     */
    static final Map<String, Set<String>> WORKFLOWS_MIT_SECRETS = Map.of("infomaniak-deploy.yml", Set.of(
            "INFOMANIAK_SSH_KEY", "ARCHIV_DB_POSTGRES", "ARCHIV_DB_OWNER", "ARCHIV_DB_APP", "KEYCLOAK_DB",
            "KEYCLOAK_ADMIN", "S3_APP_ZUGANG_ID", "S3_APP_ZUGANG_GEHEIMNIS"));

    private static final Pattern SECRETS = Pattern.compile("\\bsecrets\\b");

    /** Zugriff auf den Secrets-Kontext eines Workflows ({@code secrets.NAME}, {@code secrets[...]}, {@code toJSON(secrets)}). */
    private static final Pattern SECRET_AUSDRUCK = Pattern.compile("\\bsecrets\\s*(?:\\.\\s*([A-Za-z0-9_]+)|(\\[)|(\\)))");

    private Response antwort;

    @Wenn("ich «{word}» abfrage")
    public void abfragen(String pfad) {
        antwort = given().when().get(pfad);
    }

    @Dann("antwortet das Archiv mit Status {int} und «{word}»")
    public void antwort(int status, String text) {
        assertThat(antwort.statusCode()).isEqualTo(status);
        assertThat(antwort.body().asString()).contains(text);
    }

    @Dann("verwendet kein Workflow unter «{word}» einen Golden-Lauf oder ein Secret ausser den freigegebenen des Deploys")
    public void workflowsOhneSecretUndGolden(String verzeichnis) throws IOException {
        List<Path> workflows;
        try (Stream<Path> dateien = Files.list(REPO.resolve(verzeichnis))) {
            workflows = dateien.filter(p -> p.toString().matches(".*\\.ya?ml")).sorted().toList();
        }
        assertThat(workflows).isNotEmpty();
        for (Path workflow : workflows) {
            String name = workflow.getFileName().toString();
            String inhalt = Files.readString(workflow, StandardCharsets.UTF_8);
            Set<String> erlaubt = WORKFLOWS_MIT_SECRETS.get(name);
            if (erlaubt == null) {
                assertThat(SECRETS.matcher(inhalt).find()).as("Secret in %s", name).isFalse();
            } else {
                var treffer = SECRET_AUSDRUCK.matcher(inhalt);
                while (treffer.find()) {
                    assertThat(treffer.group(1)).as("Secret-Zugriff ohne Namen in %s", name).isNotNull();
                    assertThat(erlaubt).as("Secret in %s", name).contains(treffer.group(1));
                }
            }
            assertThat(inhalt).as(name).doesNotContain("ARCHIV_GOLDEN_DIR", "-Dgolden", "GoldenDatensatzIT");
        }
    }

    @Und("im Profil «test» zeigt «{word}» auf «{word}»")
    public void kiNurLokal(String schluessel, String host) {
        String url = ConfigProvider.getConfig().getValue(schluessel, String.class);
        assertThat(URI.create(url).getHost()).isEqualTo(host);
    }

    @Und("Requests und Antworten des Sprachmodells werden nicht geloggt")
    public void keinKiLog() {
        var config = ConfigProvider.getConfig();
        assertThat(config.getValue("quarkus.langchain4j.openai.log-requests", Boolean.class)).isFalse();
        assertThat(config.getValue("quarkus.langchain4j.openai.log-responses", Boolean.class)).isFalse();
    }

    @Dann("hat jedes Kriterium abgehakter Aufgaben ab Welle 1 ein Szenario «@req-x.y» oder einen Nachweis-Vermerk")
    public void abdeckung() {
        assertThat(ReqAbdeckung.luecken(REPO)).as("Kriterien ohne Szenario oder Nachweis-Vermerk").isEmpty();
    }
}
