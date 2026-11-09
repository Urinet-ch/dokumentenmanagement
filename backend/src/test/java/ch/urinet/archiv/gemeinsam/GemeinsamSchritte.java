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

    @Dann("verwendet der CI-Workflow «{word}» kein Secret und keinen Golden-Lauf")
    public void ciOhneSecretUndGolden(String workflow) throws IOException {
        String inhalt = Files.readString(REPO.resolve(workflow), StandardCharsets.UTF_8);
        assertThat(inhalt).doesNotContain("secrets.", "ARCHIV_GOLDEN_DIR", "-Dgolden", "GoldenDatensatzIT");
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
