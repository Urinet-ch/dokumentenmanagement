package ch.urinet.archiv.ki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.IOException;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
 */
@WithTestResource(value = KiWireMock.class, scope = TestResourceScope.GLOBAL)
public final class KiWireMock implements QuarkusTestResourceLifecycleManager {

    public static final int DIMENSION = 3584;
    public static final int TOKENS_JE_TEXT = 10;
    static final String EMBEDDING_ANTWORT = "embedding-antwort";

    private WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(options().dynamicPort().bindAddress("localhost")
                .extensions(new EmbeddingAntwort()));
        server.start();
        server.stubFor(standard());
        server.stubFor(standardEmbedding());
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
        return modell;
    }

    /** Admin-Client für den WireMock der laufenden Anwendung. */
    public static WireMock client() {
        int port = ConfigProvider.getConfig().getValue("archiv.ki.wiremock-port", Integer.class);
        return new WireMock("localhost", port);
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
}
