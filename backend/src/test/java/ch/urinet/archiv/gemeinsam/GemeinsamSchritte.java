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
import java.util.Set;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Schritte für Rauchtest und CI-Regeln (features/gemeinsam). */
public class GemeinsamSchritte {

    /** Tests laufen in {@code backend/}; das Repository liegt eine Ebene höher. */
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();

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
            // Nur der Deploy darf Secrets nutzen (Aufgabe 1.9); er schreibt sie ins Bundle und verarbeitet keine Daten
            assertThat(WorkflowSecrets.funde(inhalt, WorkflowSecrets.ERLAUBT.getOrDefault(name, Set.of())))
                    .as("unerlaubte Secrets in %s", name).isEmpty();
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
