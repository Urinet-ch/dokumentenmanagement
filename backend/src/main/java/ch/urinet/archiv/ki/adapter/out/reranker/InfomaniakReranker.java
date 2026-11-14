package ch.urinet.archiv.ki.adapter.out.reranker;

import ch.urinet.archiv.ki.adapter.out.langchain4j.EmbeddingModell;
import ch.urinet.archiv.ki.adapter.out.langchain4j.KiFehler;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.RerankerPort;
import ch.urinet.archiv.ki.domain.Neuordnung;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * REST-Adapter des {@link RerankerPort} gegen Infomaniak AI Services (Spike 0.3, Abschnitt 6; LangChain4j deckt den
 * Reranker nicht ab): {@code POST archiv.ki.reranker-url} mit {@code model}, {@code query}, {@code documents} und
 * {@code top_n}, Antwort {@code results[].index}, {@code relevance_score} und {@code usage.prompt_tokens}. Genau ein
 * Request je Aufruf ohne Wiederholung, Zeitgrenze {@code archiv.suche.reranker-zeitgrenze} für Verbindung und Antwort.
 * Token aus derselben Secret-Datei wie das Embedding-Modell ({@link EmbeddingModell#zugang(String)}); ohne Token kein
 * Request ({@link OhneZugang}). Gründe nennen nur Klassennamen oder den HTTP-Status, nie Frage, Texte oder die Antwort
 * (Req 16.7); der Adapter loggt nichts. Nur über {@code RerankerAuswahl} wählbar.
 */
@ApplicationScoped
@Typed(InfomaniakReranker.class)
public class InfomaniakReranker implements RerankerPort {

    private final EmbeddingModell zugang;
    private final ObjectMapper json;
    private final Optional<String> url;
    private final String modell;
    private final Duration zeitgrenze;
    private volatile HttpClient client;

    public InfomaniakReranker(EmbeddingModell zugang, ObjectMapper json,
                              @ConfigProperty(name = "archiv.ki.reranker-url") Optional<String> url,
                              @ConfigProperty(name = "archiv.ki.reranker-modell") String modell,
                              @ConfigProperty(name = "archiv.suche.reranker-zeitgrenze") Duration zeitgrenze) {
        this.zugang = zugang;
        this.json = json;
        this.url = url;
        this.modell = modell;
        this.zeitgrenze = zeitgrenze;
    }

    @Override
    public Antwort sortieren(String frage, List<String> texte, int topN) {
        if (texte.isEmpty() || topN < 1 || topN > texte.size()) {
            throw new IllegalArgumentException("1 bis Zahl der Texte");
        }
        Optional<String> token = url.flatMap(zugang::zugang);
        if (token.isEmpty()) {
            return new OhneZugang();
        }
        try {
            ObjectNode koerper = json.createObjectNode().put("model", modell).put("query", frage);
            texte.forEach(koerper.putArray("documents")::add);
            koerper.put("top_n", topN);
            HttpRequest anfrage = HttpRequest.newBuilder(URI.create(url.get()))
                    .timeout(zeitgrenze)
                    .header("Authorization", "Bearer " + token.get())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(koerper), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> antwort = client().send(anfrage,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (antwort.statusCode() / 100 != 2) {
                return new Ausfall("HTTP-Status " + antwort.statusCode(), verbrauch(0));
            }
            return auswerten(json.readTree(antwort.body()), texte.size(), topN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Ausfall(KiFehler.grund(e), verbrauch(0));
        } catch (IOException | RuntimeException e) {
            return new Ausfall(KiFehler.grund(e), verbrauch(0));
        }
    }

    private Antwort auswerten(JsonNode antwort, int texte, int topN) {
        JsonNode tokens = antwort.path("usage").path("prompt_tokens");
        KiPort.Verbrauch verbrauch = verbrauch(tokens.canConvertToLong() ? Math.max(0, tokens.asLong()) : 0);
        JsonNode ergebnisse = antwort.path("results");
        if (!ergebnisse.isArray()) {
            return new Ausfall("ohne results", verbrauch);
        }
        List<Integer> indizes = new ArrayList<>();
        List<Double> werte = new ArrayList<>();
        for (JsonNode ergebnis : ergebnisse) {
            JsonNode index = ergebnis.path("index");
            JsonNode wert = ergebnis.path("relevance_score");
            if (!index.isIntegralNumber() || !index.canConvertToInt() || !wert.isNumber()
                    || !Double.isFinite(wert.asDouble())) {
                return new Ausfall("ungültiges Ergebnis", verbrauch);
            }
            indizes.add(index.asInt());
            werte.add(wert.asDouble());
        }
        if (!Neuordnung.gueltig(indizes, texte, topN)) {
            return new Ausfall("ungültige Indizes", verbrauch);
        }
        return new Sortiert(indizes, werte, verbrauch);
    }

    private KiPort.Verbrauch verbrauch(long tokensEin) {
        return new KiPort.Verbrauch(modell, tokensEin, 0);
    }

    private HttpClient client() {
        HttpClient wert = client;
        if (wert == null) {
            synchronized (this) {
                if (client == null) {
                    client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(zeitgrenze)
                            .followRedirects(HttpClient.Redirect.NEVER).build();
                }
                wert = client;
            }
        }
        return wert;
    }
}
