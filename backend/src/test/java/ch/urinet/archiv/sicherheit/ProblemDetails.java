package ch.urinet.archiv.sicherheit;

import io.restassured.response.Response;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Prüft eine Fehlerantwort als Problem Details (RFC 9457, design.md «Error Handling») ohne weitere Daten. */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    /** Liefert den Fehlercode. */
    public static String pruefen(Response antwort, int status) {
        return pruefen(antwort, status, new String[0]);
    }

    /** Wie {@link #pruefen(Response, int)}, erlaubt zusätzlich die genannten Erweiterungen (z. B. {@code felder}). */
    public static String pruefen(Response antwort, int status, String... erweiterungen) {
        assertThat(antwort.statusCode()).isEqualTo(status);
        assertThat(antwort.contentType()).startsWith("application/problem+json");
        Map<String, Object> inhalt = antwort.jsonPath().getMap("");
        Set<String> erlaubt = new HashSet<>(Set.of("type", "title", "status", "detail", "instance", "fehlercode"));
        erlaubt.addAll(Arrays.asList(erweiterungen));
        assertThat(erlaubt).containsAll(inhalt.keySet());
        assertThat(inhalt).containsKeys("type", "title", "status", "detail", "instance", "fehlercode");
        String fehlercode = (String) inhalt.get("fehlercode");
        assertThat(inhalt.get("type")).isEqualTo("urn:urinet:archiv:fehler:" + fehlercode);
        assertThat(inhalt.get("status")).isEqualTo(status);
        assertThat((String) inhalt.get("instance")).matches("^urn:uuid:[0-9a-f-]{36}$");
        assertThat((String) inhalt.get("title")).isNotBlank();
        assertThat((String) inhalt.get("detail")).isNotBlank();
        return fehlercode;
    }
}
