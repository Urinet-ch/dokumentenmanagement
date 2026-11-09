package ch.urinet.archiv.spike.golden;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * ChatModel ohne Transport-Wiederholung, damit je Aufruf genau ein Request rausgeht (Req 7.1, Befund 14):
 * {@code quarkus.langchain4j.openai.max-retries} lehnt 0 ab, 1 bedeutet in LangChain4j eine Wiederholung.
 */
@ApplicationScoped
public class KiModell implements Supplier<ChatModel> {

    /** Diesen Schlüssel prüft auch die Hostprüfung (Req 16.22). */
    static final String BASIS_URL = "archiv.ki.basis-url";

    @ConfigProperty(name = BASIS_URL)
    String basisUrl;
    @ConfigProperty(name = "archiv.ki.chat-modell")
    String modell;
    @ConfigProperty(name = "archiv.ki.timeout")
    Duration timeout;
    // Token aus der Secret-Datei, gesetzt von TokenDateiConfigSourceFactory
    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String apiKey;
    @Inject
    TokenZaehler tokenZaehler;

    private volatile ChatModel chatModel;

    @Override
    public ChatModel get() {
        if (chatModel == null) {
            chatModel = baue(basisUrl, apiKey, modell, timeout, tokenZaehler);
        }
        return chatModel;
    }

    static ChatModel baue(String basisUrl, String apiKey, String modell, Duration timeout, ChatModelListener listener) {
        return OpenAiChatModel.builder()
                .baseUrl(basisUrl)
                .apiKey(apiKey)
                .modelName(modell)
                .temperature(0.0)
                // wie das frühere Standardmodell der Extension, damit Messungen vergleichbar bleiben
                .topP(1.0)
                .presencePenalty(0.0)
                .frequencyPenalty(0.0)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .strictJsonSchema(true)
                .timeout(timeout)
                .maxRetries(0)
                // nie Inhalte loggen (Req 16.7)
                .logRequests(false)
                .logResponses(false)
                .listeners(List.of(listener))
                .build();
    }
}
