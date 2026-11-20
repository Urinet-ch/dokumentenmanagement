package ch.urinet.archiv.ki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Sprachmodell für alle Tests (Aufgabe 3.2, Req 16.7, 16.21): WireMock an localhost mit freiem Port, gesetzt als
 * {@code archiv.ki.wiremock-port}; KI-Aufrufe gehen nie an echte Modelle. Ohne eigenen Stub antwortet der Standard-Stub
 * ({@link #standard()}, tiefe Priorität) ausserhalb des Schemas: zwei Aufrufe, das Dokument geht ohne Vorschlag in den
 * Eingangskorb (Req 7.3) und der Auftrag endet ohne Fehlversuch. Ein Ausfall (z. B. 404) wäre ein Fehlversuch und
 * hielte andere Tests auf. Tests steuern WireMock über die Admin-Schnittstelle ({@link #client()}), weil Ressource und
 * Test in verschiedenen Classloadern laufen.
 * <p>
 * Embedding-Modell (Spec 02, Aufgabe 6.3): Der Standard-Stub {@link #standardEmbedding()} liefert je Eingabe einen
 * Vektor mit {@link #DIMENSION} Werten, abgeleitet aus dem Text ({@link #vektor}), und {@link #TOKENS_JE_TEXT} Tokens je
 * Eingabe. So läuft jeder Auftrag {@code EMBEDDING} nach einer Archivierung ohne Fehlversuch.
 * <p>
 * Reranker (Spec 02, Aufgabe 6.4): Der Standard-Stub {@link #standardReranker()} ordnet die Texte in umgekehrter
 * Reihenfolge (der letzte gesendete zuerst), liefert genau {@code top_n} Ergebnisse und meldet
 * {@link #RERANKER_TOKENS_JE_TEXT} Tokens je Text. So unterscheidet sich seine Reihenfolge von der des RRF.
 * <p>
 * Chat-Modell (Spec 02, Aufgabe 7.2): gestreamte Antworten im Format von {@code POST /chat/completions} mit
 * {@code "stream": true} ({@link #chatStrom}: Text-Chunks, {@code tool_calls} über mehrere Chunks, mehrere Werkzeuge in
 * einer Antwort, {@code usage} am Ende). {@link #chatFolge} liefert sie der Reihe nach, {@link #chatFehler} einen
 * HTTP-Fehler, {@link #chatVerzoegert} eine Antwort nach einer Verzögerung. Die Stubs gelten nur für Anfragen mit
 * {@code stream: true} (Priorität 5); der Standard-Stub der Erfassung bleibt unberührt.
 */
@WithTestResource(value = KiWireMock.class, scope = TestResourceScope.GLOBAL)
public final class KiWireMock implements QuarkusTestResourceLifecycleManager {

    public static final int DIMENSION = 3584;
    public static final int TOKENS_JE_TEXT = 10;
    static final String EMBEDDING_ANTWORT = "embedding-antwort";
    public static final String RERANKER_PFAD = "/cohere/v2/rerank";
    public static final String RERANKER_MODELL = "BAAI/bge-reranker-v2-m3";
    public static final int RERANKER_TOKENS_JE_TEXT = 5;
    static final String RERANKER_ANTWORT = "reranker-antwort";

    private WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(options().dynamicPort().bindAddress("localhost")
                .extensions(new EmbeddingAntwort(), new RerankerAntwort()));
        server.start();
        server.stubFor(standard());
        server.stubFor(standardEmbedding());
        server.stubFor(standardReranker());
        return Map.of("archiv.ki.wiremock-port", String.valueOf(server.port()));
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    /** Standard-Stub: Antwort ausserhalb des Schemas mit Priorität 10; Stubs der Szenarien (Priorität 5) gehen vor. */
    public static MappingBuilder standard() {
        String antwort = """
                {"id":"test-standard","object":"chat.completion","created":1773565200,"model":"google/gemma-4-31B-it",\
                "choices":[{"index":0,"message":{"role":"assistant","content":"kein JSON"},"finish_reason":"stop"}],\
                "usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}""";
        return post(urlPathEqualTo("/v1/chat/completions")).atPriority(10).willReturn(okJson(antwort));
    }

    /** Standard-Stub der Embeddings mit Priorität 10: je Eingabe ein Vektor ({@link EmbeddingAntwort}). */
    public static MappingBuilder standardEmbedding() {
        return post(urlPathEqualTo("/v1/embeddings")).atPriority(10)
                .willReturn(ResponseDefinitionBuilder.responseDefinition().withTransformers(EMBEDDING_ANTWORT));
    }

    /** Standard-Stub des Rerankers mit Priorität 10: umgekehrte Reihenfolge ({@link RerankerAntwort}). */
    public static MappingBuilder standardReranker() {
        return post(urlPathEqualTo(RERANKER_PFAD)).atPriority(10)
                .willReturn(ResponseDefinitionBuilder.responseDefinition().withTransformers(RERANKER_ANTWORT));
    }

    /** Simuliert einen festen Score für alle Treffer des Rerankers. */
    public static void rerankerMitRelevanz(double relevanz) {
        if (!Double.isFinite(relevanz) || relevanz < 0 || relevanz > 1) {
            throw new IllegalArgumentException("Relevanz muss zwischen 0 und 1 liegen");
        }
        int tokens = RERANKER_TOKENS_JE_TEXT;
        String antwort = "{\"id\":\"score-test\",\"model\":\"" + RERANKER_MODELL + "\",\"usage\":"
                + "{\"prompt_tokens\":" + tokens + ",\"total_tokens\":" + tokens + "},\"results\":["
                + "{\"index\":0,\"relevance_score\":" + relevanz + "}]}";
        client().register(post(urlPathEqualTo(RERANKER_PFAD)).atPriority(1).willReturn(okJson(antwort)));
    }

    // ---- Chat-Modell (gestreamt) -------------------------------------------------------------------------------

    public static final String CHAT_PFAD = "/v1/chat/completions";
    public static final String CHAT_MODELL = "mistralai/Mistral-Small-4-119B-2603";
    private static final String CHAT_SZENARIO = "chat-modell";

    /** Ein Werkzeugaufruf des simulierten Modells: Name und Argumente als JSON-Text. */
    public record ChatAufruf(String name, String argumente) {
    }

    /**
     * Eine simulierte Modellantwort: Text und/oder Werkzeugaufrufe (mehrere in einer Antwort sind möglich) und die
     * Tokens am Ende ({@code usage}); {@code ein < 0} lässt {@code usage} weg.
     */
    public record ChatAntwort(String text, List<ChatAufruf> aufrufe, int ein, int aus) {
        public ChatAntwort {
            aufrufe = List.copyOf(aufrufe);
        }

        public static ChatAntwort text(String text, int ein, int aus) {
            return new ChatAntwort(text, List.of(), ein, aus);
        }

        public static ChatAntwort werkzeuge(int ein, int aus, ChatAufruf... aufrufe) {
            return new ChatAntwort(null, List.of(aufrufe), ein, aus);
        }
    }

    /** Antwort im Format von {@code chat.completion.chunk} (Server-Sent Events), wie die OpenAI-Schnittstelle sie streamt. */
    public static String chatStrom(ChatAntwort antwort) {
        StringBuilder sb = new StringBuilder();
        chunk(sb, "{\"role\":\"assistant\",\"content\":\"\"}", "null");
        if (antwort.text() != null) {
            for (String stueck : stuecke(antwort.text(), 12)) {
                chunk(sb, "{\"content\":" + jsonText(stueck) + "}", "null");
            }
        }
        for (int i = 0; i < antwort.aufrufe().size(); i++) {
            ChatAufruf aufruf = antwort.aufrufe().get(i);
            chunk(sb, "{\"tool_calls\":[{\"index\":" + i + ",\"id\":\"call_" + i + "\",\"type\":\"function\","
                    + "\"function\":{\"name\":" + jsonText(aufruf.name()) + ",\"arguments\":\"\"}}]}", "null");
            for (String stueck : stuecke(aufruf.argumente(), 9)) {
                chunk(sb, "{\"tool_calls\":[{\"index\":" + i + ",\"function\":{\"arguments\":" + jsonText(stueck)
                        + "}}]}", "null");
            }
        }
        chunk(sb, "{}", antwort.aufrufe().isEmpty() ? "\"stop\"" : "\"tool_calls\"");
        if (antwort.ein() >= 0) {
            sb.append("data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1773565200,"
                    + "\"model\":\"").append(CHAT_MODELL).append("\",\"choices\":[],\"usage\":{\"prompt_tokens\":")
                    .append(antwort.ein()).append(",\"completion_tokens\":").append(antwort.aus())
                    .append(",\"total_tokens\":").append(antwort.ein() + antwort.aus()).append("}}\n\n");
        }
        sb.append("data: [DONE]\n\n");
        return sb.toString();
    }

    private static void chunk(StringBuilder sb, String delta, String ende) {
        sb.append("data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1773565200,"
                + "\"model\":\"").append(CHAT_MODELL).append("\",\"choices\":[{\"index\":0,\"delta\":").append(delta)
                .append(",\"finish_reason\":").append(ende).append("}]}\n\n");
    }

    private static List<String> stuecke(String text, int laenge) {
        List<String> liste = new ArrayList<>();
        for (int i = 0; i < text.length(); i += laenge) {
            liste.add(text.substring(i, Math.min(text.length(), i + laenge)));
        }
        return liste;
    }

    private static String jsonText(String text) {
        try {
            return new ObjectMapper().writeValueAsString(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseDefinitionBuilder strom(ChatAntwort antwort) {
        return ResponseDefinitionBuilder.responseDefinition().withStatus(200)
                .withHeader("Content-Type", "text/event-stream; charset=utf-8").withBody(chatStrom(antwort));
    }

    private static MappingBuilder gestreamt() {
        return post(urlPathEqualTo(CHAT_PFAD)).withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                .atPriority(5);
    }

    /**
     * Das simulierte Chat-Modell antwortet der Reihe nach mit {@code antworten} (eine je gestreamter Anfrage); jede weitere
     * Anfrage erhält die letzte Antwort noch einmal, damit eine Endlosschleife von Werkzeugaufrufen möglich ist.
     */
    public static void chatFolge(List<ChatAntwort> antworten) {
        chatFolge(antworten, 0);
    }

    /** Wie {@link #chatFolge(List)}; die erste Antwort kommt über {@code erstePortionenMillis} ms verteilt (0: sofort). */
    public static void chatFolge(List<ChatAntwort> antworten, int erstePortionenMillis) {
        WireMock modell = client();
        for (int i = 0; i < antworten.size(); i++) {
            boolean letzte = i == antworten.size() - 1;
            var stub = gestreamt().inScenario(CHAT_SZENARIO)
                    .whenScenarioStateIs(i == 0 ? com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
                            : "antwort-" + i)
                    .willReturn(i == 0 && erstePortionenMillis > 0
                            ? strom(antworten.get(i)).withChunkedDribbleDelay(20, erstePortionenMillis)
                            : strom(antworten.get(i)));
            if (!letzte) {
                stub.willSetStateTo("antwort-" + (i + 1));
            }
            modell.register(stub);
        }
    }

    /** Das simulierte Chat-Modell antwortet mit einem HTTP-Fehler. */
    public static void chatFehler(int status) {
        client().register(gestreamt().willReturn(ResponseDefinitionBuilder.responseDefinition().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":{\"message\":\"simuliert\"}}")));
    }

    /** Das simulierte Chat-Modell antwortet erst nach {@code millis} ms (über der Zeitgrenze des Agenten). */
    public static void chatVerzoegert(ChatAntwort antwort, int millis) {
        client().register(gestreamt().willReturn(strom(antwort).withFixedDelay(millis)));
    }

    /** Antworten auf die nicht gestreamten JSON-Schema-Aufrufe des Wissens-Ingests, in der angegebenen Reihenfolge. */
    public static void wissenFolge(List<String> inhalte) {
        wissenFolge(inhalte, 0);
    }

    /** Wie {@link #wissenFolge(List)}; verzögert optional die erste Antwort für Warteschlangen-Szenarien. */
    public static void wissenFolge(List<String> inhalte, int ersteVerzoegerungMillis) {
        wissenFolge(inhalte, ersteVerzoegerungMillis, 100, 50);
    }

    /** Wie {@link #wissenFolge(List, int)}; setzt den simulierten Verbrauch je Antwort. */
    public static void wissenFolge(List<String> inhalte, int ersteVerzoegerungMillis,
                                   int tokensEin, int tokensAus) {
        wissenFolge(inhalte, ersteVerzoegerungMillis, tokensEin, tokensAus, null);
    }

    /** Antwortfolge nur für die mit einem eindeutigen Fixture-Marker versehenen Anfragen. */
    public static void wissenFolge(List<String> inhalte, int ersteVerzoegerungMillis,
                                   int tokensEin, int tokensAus, String marker) {
        WireMock modell = client();
        for (int i = 0; i < inhalte.size(); i++) {
            boolean letzte = i == inhalte.size() - 1;
            ResponseDefinitionBuilder antwort = okJson(wissenAntwort(inhalte.get(i), tokensEin, tokensAus));
            if (i == 0 && ersteVerzoegerungMillis > 0) {
                antwort.withFixedDelay(ersteVerzoegerungMillis);
            }
            var stub = post(urlPathEqualTo(CHAT_PFAD))
                    .withRequestBody(matchingJsonPath("$.response_format.json_schema.name",
                            matching("wissen_ingest_.*")))
                    .atPriority(5)
                    .inScenario(marker == null ? "wissen-ingest" : "wissen-ingest-" + marker)
                    .whenScenarioStateIs(i == 0 ? com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
                            : "antwort-" + i)
                    .willReturn(antwort);
            if (marker != null) {
                stub.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing(marker));
            }
            if (!letzte) {
                stub.willSetStateTo("antwort-" + (i + 1));
            }
            modell.register(stub);
        }
    }

    /** JSON-Anfragen aller vier Wissens-Ingest-Schritte aus dem WireMock-Journal. */
    public static List<String> wissenAnfragen() {
        return client().find(RequestPatternBuilder.newRequestPattern(
                        com.github.tomakehurst.wiremock.http.RequestMethod.POST, urlPathEqualTo(CHAT_PFAD))
                .withRequestBody(matchingJsonPath("$.response_format.json_schema.name",
                        matching("wissen_ingest_.*"))))
                .stream().map(LoggedRequest::getBodyAsString).toList();
    }

    private static String wissenAntwort(String inhalt, int tokensEin, int tokensAus) {
        return """
                {"id":"wissen-test","object":"chat.completion","created":1773565200,
                 "model":"google/gemma-4-31B-it",
                 "choices":[{"index":0,"message":{"role":"assistant","content":%s},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d}}
                """.formatted(jsonText(inhalt), tokensEin, tokensAus, tokensEin + tokensAus);
    }

    /** Gestreamte Anfragen an das Chat-Modell im Journal, älteste zuerst, als JSON-Text. */
    public static List<String> chatAnfragen() {
        List<String> koerper = new ArrayList<>();
        for (LoggedRequest anfrage : client().find(
                RequestPatternBuilder.newRequestPattern(com.github.tomakehurst.wiremock.http.RequestMethod.POST,
                        urlPathEqualTo(CHAT_PFAD)).withRequestBody(matchingJsonPath("$.stream", equalTo("true"))))) {
            koerper.add(anfrage.getBodyAsString());
        }
        return koerper;
    }

    /** Vektor zum Text: deterministisch, nicht normiert, nie der Nullvektor. */
    public static float[] vektor(String text) {
        float[] vektor = new float[DIMENSION];
        int h = text.hashCode();
        for (int i = 0; i < DIMENSION; i++) {
            vektor[i] = (Math.floorMod(h + i * 31, 1000) + 1) / 500f;
        }
        return vektor;
    }

    /** Setzt den WireMock der laufenden Anwendung zurück und registriert wieder den Standard-Stub. */
    public static WireMock zuruecksetzen() {
        WireMock modell = client();
        modell.resetMappings();
        modell.resetRequests();
        modell.resetScenarios();
        modell.register(standard());
        modell.register(standardEmbedding());
        modell.register(standardReranker());
        return modell;
    }

    /** Admin-Client für den WireMock der laufenden Anwendung. */
    public static WireMock client() {
        int port = ConfigProvider.getConfig().getValue("archiv.ki.wiremock-port", Integer.class);
        return new WireMock("localhost", port);
    }

    /** {@code top_n} des letzten Aufrufs, ohne Suchfrage oder Texte auszugeben. */
    public static int letzterRerankerTopN() {
        List<LoggedRequest> anfragen = client().find(postRequestedFor(urlPathEqualTo(RERANKER_PFAD)));
        if (anfragen.isEmpty()) {
            throw new IllegalStateException("Reranker wurde nicht aufgerufen");
        }
        try {
            return new ObjectMapper().readTree(anfragen.getLast().getBodyAsString()).path("top_n").asInt(-1);
        } catch (IOException e) {
            throw new IllegalStateException("Reranker-Anfrage nicht lesbar", e);
        }
    }

    /** Score des letzten Reranker-Aufrufs; prüft, dass ein Szenario-Stub tatsächlich geantwortet hat. */
    public static double letzterRerankerScore() {
        ServeEvent ereignis = client().getServeEvents().stream()
                .filter(e -> RERANKER_PFAD.equals(e.getRequest().getUrl()))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new IllegalStateException("Reranker wurde nicht aufgerufen"));
        try {
            return new ObjectMapper().readTree(ereignis.getResponse().getBodyAsString()).path("results").path(0)
                    .path("relevance_score").asDouble(Double.NaN);
        } catch (IOException e) {
            throw new IllegalStateException("Reranker-Antwort nicht lesbar", e);
        }
    }

    /** Antwort im Format von {@code POST /embeddings} (OpenAI-kompatibel) zu den Eingaben der Anfrage. */
    static final class EmbeddingAntwort implements ResponseDefinitionTransformerV2 {

        private static final ObjectMapper JSON = new ObjectMapper();

        @Override
        public ResponseDefinition transform(ServeEvent ereignis) {
            try {
                JsonNode eingaben = JSON.readTree(ereignis.getRequest().getBodyAsString()).path("input");
                StringBuilder daten = new StringBuilder();
                for (int i = 0; i < eingaben.size(); i++) {
                    if (i > 0) {
                        daten.append(',');
                    }
                    daten.append("{\"object\":\"embedding\",\"index\":").append(i).append(",\"embedding\":")
                            .append(JSON.writeValueAsString(vektor(eingaben.get(i).asText()))).append('}');
                }
                int tokens = TOKENS_JE_TEXT * eingaben.size();
                String koerper = "{\"object\":\"list\",\"data\":[" + daten + "],\"model\":\"bge_multilingual_gemma2\","
                        + "\"usage\":{\"prompt_tokens\":" + tokens + ",\"total_tokens\":" + tokens + "}}";
                return ResponseDefinitionBuilder.responseDefinition().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(koerper).build();
            } catch (IOException e) {
                return ResponseDefinitionBuilder.responseDefinition().withStatus(400).build();
            }
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public String getName() {
            return EMBEDDING_ANTWORT;
        }
    }

    /** Antwort im Format von {@code POST /cohere/v2/rerank} (Spike 0.3): umgekehrte Reihenfolge, {@code top_n} Stück. */
    static final class RerankerAntwort implements ResponseDefinitionTransformerV2 {

        private static final ObjectMapper JSON = new ObjectMapper();

        @Override
        public ResponseDefinition transform(ServeEvent ereignis) {
            try {
                JsonNode anfrage = JSON.readTree(ereignis.getRequest().getBodyAsString());
                int texte = anfrage.path("documents").size();
                int topN = anfrage.path("top_n").asInt(texte);
                StringBuilder ergebnisse = new StringBuilder();
                for (int i = 0; i < Math.min(topN, texte); i++) {
                    if (i > 0) {
                        ergebnisse.append(',');
                    }
                    ergebnisse.append("{\"index\":").append(texte - 1 - i).append(",\"relevance_score\":")
                            .append(1.0 - i / 100.0).append('}');
                }
                int tokens = RERANKER_TOKENS_JE_TEXT * texte;
                String koerper = "{\"id\":\"score-test\",\"model\":\"" + RERANKER_MODELL + "\",\"usage\":"
                        + "{\"prompt_tokens\":" + tokens + ",\"total_tokens\":" + tokens + "},\"results\":["
                        + ergebnisse + "]}";
                // eine Verzögerung des Stubs bleibt erhalten (Szenarien zur Zeitgrenze)
                return ResponseDefinitionBuilder.responseDefinition().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(koerper)
                        .withFixedDelay(ereignis.getResponseDefinition().getFixedDelayMilliseconds()).build();
            } catch (IOException e) {
                return ResponseDefinitionBuilder.responseDefinition().withStatus(400).build();
            }
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public String getName() {
            return RERANKER_ANTWORT;
        }
    }
}
