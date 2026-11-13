package ch.urinet.archiv.ki.adapter.out.langchain4j;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
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
 * (tech.md Regel 7; in prod Pflicht über {@code archiv.secrets.pflicht}). Ohne Token geht kein Request hinaus
 * ({@link #bereit()}); nur gegen ein lokales Ziel (WireMock in {@code test}, LM Studio in {@code lokal}) gilt ein
 * Platzhalter. Nie Inhalte loggen (Req 16.7).
 */
@ApplicationScoped
public class KiModell implements Supplier<ChatModel> {

    /** Platzhalter ohne Secret-Datei, nur für ein lokales Ziel (WireMock, LM Studio); nie an Infomaniak gesendet. */
    static final String LOKAL_OHNE_TOKEN = "lokal-ohne-token";

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
                    String zugang = zugang(token, basisUrl).orElseThrow(
                            () -> new IllegalStateException("kein Token für das Sprachmodell"));
                    chatModel = baue(basisUrl, zugang, this.modell, timeout, tokenZaehler);
                }
                modell = chatModel;
            }
        }
        return modell;
    }

    /** {@code true}, wenn ein Request hinausgehen darf: Token vorhanden oder lokales Ziel. */
    public boolean bereit() {
        return zugang(token, basisUrl).isPresent();
    }

    /** Token aus der Secret-Datei; ohne Token nur für ein lokales Ziel ein Platzhalter, sonst leer. */
    static Optional<String> zugang(Optional<String> token, String basisUrl) {
        Optional<String> wert = token.map(String::strip).filter(t -> !t.isEmpty());
        if (wert.isPresent()) {
            return wert;
        }
        return lokal(basisUrl) ? Optional.of(LOKAL_OHNE_TOKEN) : Optional.empty();
    }

    private static boolean lokal(String basisUrl) {
        try {
            String host = URI.create(basisUrl).getHost();
            return "localhost".equals(host) || "127.0.0.1".equals(host);
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
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
