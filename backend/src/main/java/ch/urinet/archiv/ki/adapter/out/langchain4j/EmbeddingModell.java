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
 * loggen (Req 16.7). Für die Frage der Hybridsuche (Aufgabe 6.4) ein zweites Modell mit derselben Konfiguration und der
 * kurzen Zeitgrenze {@code archiv.suche.embedding-zeitgrenze} ({@link #suche()}); der Reranker liest das Token über
 * {@link #zugang(String)}.
 */
@ApplicationScoped
public class EmbeddingModell implements Supplier<EmbeddingModel> {

    @ConfigProperty(name = "archiv.ki.basis-url")
    String basisUrl;
    @ConfigProperty(name = "archiv.ki.embedding-modell")
    String modell;
    @ConfigProperty(name = "archiv.ki.timeout")
    Duration timeout;
    @ConfigProperty(name = "archiv.suche.embedding-zeitgrenze")
    Duration sucheZeitgrenze;
    @ConfigProperty(name = "infomaniak_ai_token")
    Optional<String> token;

    private volatile EmbeddingModel embeddingModel;
    private volatile EmbeddingModel sucheModel;

    @Override
    public EmbeddingModel get() {
        EmbeddingModel wert = embeddingModel;
        if (wert == null) {
            synchronized (this) {
                if (embeddingModel == null) {
                    embeddingModel = bauen(timeout);
                }
                wert = embeddingModel;
            }
        }
        return wert;
    }

    /** Modell für die Frage der Hybridsuche mit {@code archiv.suche.embedding-zeitgrenze}. */
    public EmbeddingModel suche() {
        EmbeddingModel wert = sucheModel;
        if (wert == null) {
            synchronized (this) {
                if (sucheModel == null) {
                    sucheModel = bauen(sucheZeitgrenze);
                }
                wert = sucheModel;
            }
        }
        return wert;
    }

    private EmbeddingModel bauen(Duration zeitgrenze) {
        String zugang = KiModell.zugang(token, basisUrl).orElseThrow(
                () -> new IllegalStateException("kein Token für das Embedding-Modell"));
        return OpenAiEmbeddingModel.builder()
                .baseUrl(basisUrl)
                .apiKey(zugang)
                .modelName(modell)
                .timeout(zeitgrenze)
                .maxRetries(0)
                .maxSegmentsPerBatch(EmbeddingPort.BATCH)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * Token aus derselben Secret-Datei {@code infomaniak_ai_token} für einen anderen Endpunkt von Infomaniak AI Services
     * (Reranker); ohne Token nur für ein lokales Ziel ein Platzhalter, sonst leer.
     */
    public Optional<String> zugang(String url) {
        return KiModell.zugang(token, url);
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
