package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.sicherheit.ProblemDetails;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import org.jboss.logmanager.ExtLogRecord;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zu Problem Details: unerwartete Fehler verraten weder in der Antwort noch im Log Inhalte (Req 16.7). */
public class ProblemDetailsSchritte {

    private static final String PROBLEM_LOGGER = "ch.urinet.archiv.gemeinsam.adapter.in.rest.ProblemAntwort";

    private final List<LogRecord> eintraege = new CopyOnWriteArrayList<>();
    private final SimpleFormatter format = new SimpleFormatter();
    private Handler mithoerer;
    private Response antwort;

    @Wenn("«{word}» einen unerwarteten Fehler mit dem Text «{word}» auslöst")
    public void fehlerAusloesen(String benutzer, String text) {
        mithoerer = new Handler() {
            @Override
            public void publish(LogRecord eintrag) {
                eintraege.add(eintrag);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        mithoerer.setLevel(Level.ALL);
        Logger.getLogger("").addHandler(mithoerer);
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get("/api/v1/probe/fehler/" + text);
    }

    @Dann("antwortet das Archiv mit {int} und Fehlercode «{word}»")
    public void mitFehlercode(int status, String fehlercode) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(fehlercode);
    }

    @Dann("weder die Antwort noch das Log enthalten «{word}»")
    public void ohneInhalt(String text) {
        assertThat(antwort.asString()).doesNotContain(text).doesNotContain("IllegalStateException")
                .doesNotContain("\tat ");
        assertThat(eintraege).isNotEmpty();
        assertThat(eintraege).extracting(this::volltext).noneMatch(zeile -> zeile.contains(text));
    }

    @Dann("das Log nennt nur Fehler-ID, Status und Fehlercode")
    public void nurIds() {
        String instanz = antwort.jsonPath().getString("instance").substring("urn:uuid:".length());
        assertThat(eintraege).filteredOn(e -> PROBLEM_LOGGER.equals(e.getLoggerName())).singleElement()
                .satisfies(e -> {
                    assertThat(e.getThrown()).isNull();
                    assertThat(nachricht(e))
                            .isEqualTo("Fehler " + instanz + ": status=500 fehlercode=INTERNER_FEHLER");
                });
    }

    private String volltext(LogRecord eintrag) {
        StringWriter text = new StringWriter();
        text.append(nachricht(eintrag));
        if (eintrag.getThrown() != null) {
            eintrag.getThrown().printStackTrace(new PrintWriter(text));
        }
        return text.toString();
    }

    private String nachricht(LogRecord eintrag) {
        return eintrag instanceof ExtLogRecord ext ? ext.getFormattedMessage() : format.formatMessage(eintrag);
    }

    @After
    public void aufraeumen() {
        if (mithoerer != null) {
            Logger.getLogger("").removeHandler(mithoerer);
        }
    }
}
