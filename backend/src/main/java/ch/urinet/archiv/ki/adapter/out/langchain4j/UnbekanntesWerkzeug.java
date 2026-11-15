package ch.urinet.archiv.ki.adapter.out.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.function.Function;

/**
 * Antwort auf einen Werkzeugnamen, den der Durchgang nicht angeboten hat (Spec 02, Req 5.3; Aufgabe 7.2): Es wird nichts
 * ausgeführt, das Modell erhält {@code {"fehler":"unbekanntes Werkzeug"}}. Das gilt für erfundene Namen, für Werkzeuge mit
 * Nebenwirkungen (es gibt keine) und für lesende Werkzeuge, die dieser Rolle nicht angeboten werden. Der Name erscheint
 * weder in der Antwort noch im Log.
 */
@ApplicationScoped
public class UnbekanntesWerkzeug implements Function<ToolExecutionRequest, ToolExecutionResultMessage> {

    static final String FEHLER = "{\"fehler\":\"unbekanntes Werkzeug\"}";

    @Override
    public ToolExecutionResultMessage apply(ToolExecutionRequest anfrage) {
        return ToolExecutionResultMessage.from(anfrage, FEHLER);
    }
}
