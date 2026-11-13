package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.EmbeddingPort;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Embedding-Modell {@code archiv.ki.embedding-modell} gegen {@code archiv.ki.basis-url} (Spec 02, design.md «Embeddings
 * und Hybridsuche»; Spike 0.3): ohne {@code dimensions} und ohne {@code encoding_format} (Infomaniak lehnt
 * {@code dimensions} ab), ohne Transport-Wiederholung ({@code maxRetries(0)}, wiederholt wird der Auftrag), höchstens
 * {@link ch.urinet.archiv.ki.application.EmbeddingPort#BATCH} Texte je Request. Token wie {@link KiModell}; nie Inhalte
 * loggen (Req 16.7).
 */
@ApplicationScoped
public class EmbeddingModell implements Supplier<EmbeddingModel> {

    @ConfigProperty(name = "archiv.ki.basis-url")
    String basisUrl;
    @ConfigProperty(name = "archiv.ki.embedding-modell")
    String modell;
    @ConfigProperty(name = "archiv.ki.timeout")
    Duration timeout;
    @ConfigProperty(name = "infomaniak_ai_token")
    Optional<String> token;

    private volatile EmbeddingModel embeddingModel;

    @Override
    public EmbeddingModel get() {
        EmbeddingModel wert = embeddingModel;
        if (wert == null) {
            synchronized (this) {
                if (embeddingModel == null) {
                    String zugang = KiModell.zugang(token, basisUrl).orElseThrow(
                            () -> new IllegalStateException("kein Token für das Embedding-Modell"));
                    embeddingModel = OpenAiEmbeddingModel.builder()
                            .baseUrl(basisUrl)
                            .apiKey(zugang)
                            .modelName(modell)
                            .timeout(timeout)
                            .maxRetries(0)
                            .maxSegmentsPerBatch(EmbeddingPort.BATCH)
                            .logRequests(false)
                            .logResponses(false)
                            .build();
                }
                wert = embeddingModel;
            }
        }
        return wert;
    }

    /** {@code true}, wenn ein Request hinausgehen darf: Token vorhanden oder lokales Ziel. */
    public boolean bereit() {
        return KiModell.zugang(token, basisUrl).isPresent();
    }

    /** Modellname für {@code ki_verbrauch}. */
    public String modell() {
        return modell;
    }
}
