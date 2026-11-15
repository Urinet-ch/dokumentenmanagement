package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.verarbeitung.application.SpeicherMessung;
import ch.urinet.archiv.verarbeitung.application.SpeicherUeberwachung;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zur Speicher-Frühwarnung (Req 16.5, Aufgabe 5.6): Mail an Mailpit, Kennzahl unter {@code /q/metrics}. */
public class SpeicherSchritte {

    private static final long MIB = 1024 * 1024;

    @Inject
    SpeicherUeberwachung speicher;

    // nur über Compose Dev Services gesetzt, wie in UeberwachungSchritte
    @ConfigProperty(name = "archiv.entwicklung.mailpit-port")
    Optional<Integer> mailpitPort;

    private final Map<String, SpeicherMessung> messungen = new LinkedHashMap<>();

    @Angenommen("der Speicherbereich «{word}» ist zu {int} Prozent belegt")
    public void belegt(String pool, int prozent) {
        messungen.put(pool, new SpeicherMessung(pool, prozent * MIB, 100 * MIB));
    }

    @Angenommen("der Speicherbereich «{word}» hat kein Maximum")
    public void ohneMaximum(String pool) {
        messungen.put(pool, new SpeicherMessung(pool, 100 * MIB, -1));
    }

    @Wenn("die Speicherüberwachung läuft")
    public void laeuft() {
        speicher.pruefen(List.copyOf(messungen.values()));
    }

    @Dann("hat der Inhaber zu «{word}» {int} Mail zur Stufe «{word}» erhalten, die nur Bereich, Prozent und Mengen nennt")
    public void mail(String pool, int anzahl, String stufe) {
        List<Map<String, Object>> treffer = mails(pool).stream()
                .filter(m -> String.valueOf(m.get("Subject")).contains(stufe)).toList();
        assertThat(treffer).hasSize(anzahl);
        Response nachricht = given().baseUri(mailpit()).when().get("/api/v1/message/" + treffer.get(0).get("ID"));
        assertThat(nachricht.jsonPath().<String>getList("To.Address")).containsExactly("inhaber@example.invalid");
        assertThat(nachricht.jsonPath().getString("Text")).contains(pool + " bei ").contains(" MiB)")
                .doesNotContainPattern("\\d+\\.\\d+\\.\\d+\\.\\d+").doesNotContain("localhost");
    }

    @Dann("hat der Inhaber zu «{word}» keine Speicher-Mail erhalten")
    public void keineMail(String pool) {
        assertThat(mails(pool)).isEmpty();
    }

    @Dann("nennen die Kennzahlen den Speicherbereich «{word}» mit {int} Prozent ohne Mandanten-Etikett")
    public void kennzahl(String pool, int prozent) {
        Response antwort = given().when().get("/q/metrics");
        assertThat(antwort.statusCode()).isEqualTo(200);
        Matcher zeile = Pattern.compile("(?m)^archiv_jvm_speicher_prozent\\{([^}]*pool=\"" + Pattern.quote(pool)
                + "\"[^}]*)} (\\S+)$").matcher(antwort.asString());
        assertThat(zeile.find()).as("Kennzahl zu " + pool).isTrue();
        assertThat(zeile.group(1)).doesNotContain("mandant");
        assertThat(Double.parseDouble(zeile.group(2))).isEqualTo(prozent);
    }

    private List<Map<String, Object>> mails(String pool) {
        Response nachrichten = given().baseUri(mailpit()).queryParam("query", "subject:\"" + pool + "\"")
                .when().get("/api/v1/search");
        assertThat(nachrichten.statusCode()).isEqualTo(200);
        List<Map<String, Object>> liste = nachrichten.jsonPath().getList("messages");
        return liste == null ? List.of() : liste;
    }

    private String mailpit() {
        return "http://localhost:" + mailpitPort.orElseThrow();
    }
}
