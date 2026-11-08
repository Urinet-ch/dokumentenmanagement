package ch.urinet.archiv.spike.ki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Reranker über die Cohere-kompatible Schnittstelle von Infomaniak (nicht Teil der OpenAI-API). */
@ApplicationScoped
class RerankerClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    @ConfigProperty(name = "archiv.ki.reranker-url")
    String url;

    @ConfigProperty(name = "archiv.ki.reranker-modell")
    String modell;

    @ConfigProperty(name = TokenDateiConfigSourceFactory.TOKEN_DATEI)
    Path tokenDatei;

    JsonNode rerank(String frage, List<String> dokumente, int topN) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "model", modell, "query", frage, "documents", dokumente, "top_n", topN));
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + TokenDateiConfigSourceFactory.lies(tokenDatei))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> antwort = http.send(anfrage, HttpResponse.BodyHandlers.ofString());
        if (antwort.statusCode() != 200) {
            throw new IllegalStateException("Reranker HTTP " + antwort.statusCode() + ": " + antwort.body());
        }
        return json.readTree(antwort.body());
    }
}
