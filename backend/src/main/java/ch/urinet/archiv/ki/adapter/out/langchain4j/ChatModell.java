package ch.urinet.archiv.ki.adapter.out.langchain4j;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprachmodell des Chat-Agenten (Spec 02, Aufgabe 7.2; design.md «Chat-Agent»): {@code archiv.ki.chat-modell}, streamend
 * und OpenAI-kompatibel, unabhängig von {@link KiModell} der Erfassung (anderes Modell, kein JSON-Schema, kein
 * Gate-Lauf). Gleich sind Basis-URL ({@code archiv.ki.basis-url}, Startprüfung {@code KiBasisUrlPruefung}, Req 16.21) und
 * Zugang: Das Token der Secret-Datei {@code infomaniak_ai_token} liest {@link KiModell#zugang} gemeinsam; ohne Token
 * geht kein Request hinaus ({@link #bereit()}), nur gegen ein lokales Ziel gilt ein Platzhalter. Parameter nach
 * {@code archiv.ki.chat.*}: {@code temperatur}, {@code max-tokens}, {@code zeitgrenze} je Modellaufruf. Das Streaming-Modell
 * kennt keine Transport-Wiederholung (kein {@code maxRetries}); je Aufruf geht genau ein Request hinaus. Die Rückrufe
 * des Streamings laufen nicht auf dem Event-Loop-Thread des HTTP-Clients ({@link EntkoppeltesModell}), weil dort die
 * blockierenden Werkzeuge ausgeführt würden. Nie Inhalte loggen (Req 16.7).
 */
@ApplicationScoped
public class ChatModell implements Supplier<StreamingChatModel> {

    @ConfigProperty(name = "archiv.ki.basis-url")
    String basisUrl;
    @ConfigProperty(name = "archiv.ki.chat-modell")
    String modell;
    @ConfigProperty(name = "archiv.ki.chat.temperatur")
    double temperatur;
    @ConfigProperty(name = "archiv.ki.chat.max-tokens")
    int maxTokens;
    @ConfigProperty(name = "archiv.ki.chat.zeitgrenze")
    Duration zeitgrenze;
    @ConfigProperty(name = "infomaniak_ai_token")
    Optional<String> token;

    private volatile StreamingChatModel chatModell;

    @Override
    public StreamingChatModel get() {
        StreamingChatModel m = chatModell;
        if (m == null) {
            synchronized (this) {
                if (chatModell == null) {
                    String zugang = KiModell.zugang(token, basisUrl).orElseThrow(
                            () -> new IllegalStateException("kein Token für das Sprachmodell"));
                    chatModell = new EntkoppeltesModell(baue(basisUrl, zugang, modell, temperatur, maxTokens, zeitgrenze));
                }
                m = chatModell;
            }
        }
        return m;
    }

    /** {@code true}, wenn ein Request hinausgehen darf: Token vorhanden oder lokales Ziel. */
    public boolean bereit() {
        return KiModell.zugang(token, basisUrl).isPresent();
    }

    /** Modellname für {@code ki_verbrauch}. */
    public String modell() {
        return modell;
    }

    static StreamingChatModel baue(String basisUrl, String apiKey, String modell, double temperatur, int maxTokens,
                                   Duration zeitgrenze) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(basisUrl)
                .apiKey(apiKey)
                .modelName(modell)
                .temperature(temperatur)
                .maxTokens(maxTokens)
                .timeout(zeitgrenze)
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
