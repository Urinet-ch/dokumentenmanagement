package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Problem Details nach RFC 9457 (design.md «Error Handling»): {@code type}, {@code title}, {@code status},
 * {@code detail}, {@code instance} und {@code fehlercode}. Titel und Detail sind die festen Texte des
 * {@link Fehlercode}s; Eingabewerte, Inhalte, Klassennamen und Stacktraces gelangen weder in die Antwort noch ins Log
 * (Req 16.7). {@code instance} ist eine neue Fehler-ID, die auch im Log steht.
 */
public record ProblemAntwort(String fehlerId, int status, Fehlercode code, String json) {

    public static final String MEDIENTYP = "application/problem+json";
    public static final String TYP_PRAEFIX = "urn:urinet:archiv:fehler:";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = Logger.getLogger(ProblemAntwort.class);

    public static ProblemAntwort fuer(Fehlercode code) {
        return fuer(code, code.status());
    }

    /** {@code status} weicht nur bei {@code ANFRAGE_UNGUELTIG} für andere 4xx des Frameworks (z. B. 405) ab. */
    public static ProblemAntwort fuer(Fehlercode code, int status) {
        String fehlerId = UUID.randomUUID().toString();
        Map<String, Object> inhalt = new LinkedHashMap<>();
        inhalt.put("type", TYP_PRAEFIX + code.name());
        inhalt.put("title", code.titel());
        inhalt.put("status", status);
        inhalt.put("detail", code.detail());
        inhalt.put("instance", "urn:uuid:" + fehlerId);
        inhalt.put("fehlercode", code.name());
        if (status >= 500) {
            LOG.errorf("Fehler %s: status=%d fehlercode=%s", fehlerId, status, code.name());
        } else {
            LOG.debugf("Fehler %s: status=%d fehlercode=%s", fehlerId, status, code.name());
        }
        try {
            return new ProblemAntwort(fehlerId, status, code, JSON.writeValueAsString(inhalt));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Problem Details nicht serialisierbar", e);
        }
    }
}
