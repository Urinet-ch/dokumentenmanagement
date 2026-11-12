package ch.urinet.archiv.ki.adapter.out.langchain4j;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprachmodell der Erfassung (design.md «KI»): {@code archiv.ki.erfassung-modell} mit den Parametern des Gate-Laufs
 * ({@code temperature} 0, {@code top_p} 1, {@code presence_penalty} und {@code frequency_penalty} 0, {@code json_schema}
 * mit {@code strict}), ohne Transport-Wiederholung ({@code maxRetries(0)}), damit je Aufruf genau ein Request rausgeht
 * (Req 7.1); {@code quarkus.langchain4j.openai.max-retries} lehnt 0 ab. Basis-URL aus {@code archiv.ki.basis-url}
 * (Startprüfung {@code KiBasisUrlPruefung}, Req 16.21), Token aus der Secret-Datei {@code infomaniak_ai_token}
 * (tech.md Regel 7). Nie Inhalte loggen (Req 16.7).
 */
@ApplicationScoped
public class KiModell implements Supplier<ChatModel> {

    /** Ohne Secret-Datei (dev, test, lokal) ein Platzhalter; Infomaniak lehnt ihn ab, der Vorschlag fällt dann aus. */
    static final String OHNE_TOKEN = "ohne-token";

    @ConfigProperty(name = "archiv.ki.basis-url")
    String basisUrl;
    @ConfigProperty(name = "archiv.ki.erfassung-modell")
    String modell;
    @ConfigProperty(name = "archiv.ki.timeout")
    Duration timeout;
    @ConfigProperty(name = "infomaniak_ai_token")
    Optional<String> token;
    @Inject
    TokenZaehler tokenZaehler;

    private volatile ChatModel chatModel;

    @Override
    public ChatModel get() {
        ChatModel modell = chatModel;
        if (modell == null) {
            synchronized (this) {
                if (chatModel == null) {
                    chatModel = baue(basisUrl, token.map(String::strip).filter(t -> !t.isEmpty()).orElse(OHNE_TOKEN),
                            this.modell, timeout, tokenZaehler);
                }
                modell = chatModel;
            }
        }
        return modell;
    }

    /** Modellname für {@code ki_verbrauch}. */
    public String modell() {
        return modell;
    }

    static ChatModel baue(String basisUrl, String apiKey, String modell, Duration timeout, ChatModelListener listener) {
        return OpenAiChatModel.builder()
                .baseUrl(basisUrl)
                .apiKey(apiKey)
                .modelName(modell)
                .temperature(0.0)
                .topP(1.0)
                .presencePenalty(0.0)
                .frequencyPenalty(0.0)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .strictJsonSchema(true)
                .timeout(timeout)
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .listeners(List.of(listener))
                .build();
    }
}
