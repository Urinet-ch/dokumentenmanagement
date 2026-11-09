package ch.urinet.archiv.spike.golden;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Req 7.1: genau ein Request je Aufruf, auch bei HTTP 502; json_schema strict, temperature 0, konfiguriertes Modell. */
@QuarkusTest
@QuarkusTestResource(value = KiModellTest.WireMockRessource.class, restrictToAnnotatedClass = true)
class KiModellTest {

    static WireMockServer server;
    static final String PFAD = "/v1/chat/completions";

    public static class WireMockRessource implements QuarkusTestResourceLifecycleManager {
        @Override
        public Map<String, String> start() {
            server = new WireMockServer(wireMockConfig().dynamicPort());
            server.start();
            String url = "http://localhost:" + server.port() + "/v1";
            return Map.of("archiv.ki.basis-url", url, "quarkus.langchain4j.openai.base-url", url,
                    "quarkus.langchain4j.openai.api-key", "test", "archiv.ki.timeout", "10s");
        }

        @Override
        public void stop() {
            server.stop();
        }
    }

    @Inject
    MetadatenExtraktor extraktor;
    @Inject
    TokenZaehler tokenZaehler;

    @BeforeEach
    void zuruecksetzen() {
        server.resetAll();
        tokenZaehler.zuruecksetzen();
    }

    @Test
    void requestMitStrictJsonSchemaTemperaturNullUndModell() {
        server.stubFor(post(urlEqualTo(PFAD)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"id":"x","object":"chat.completion","created":0,"model":"m",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"{\\"art\\":\\"QUITTUNG\\"}"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}
                        """)));

        var antwort = extraktor.extrahiere("System", Prompt.qr(Optional.empty()), "Text");

        assertThat(antwort.content().art()).isEqualTo(MetadatenVorschlag.ArtCode.QUITTUNG);
        server.verify(1, postRequestedFor(urlEqualTo(PFAD))
                .withRequestBody(matchingJsonPath("$.model", equalTo("mistralai/Mistral-Small-4-119B-2603")))
                .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_schema")))
                .withRequestBody(matchingJsonPath("$.response_format.json_schema.strict", equalTo("true")))
                .withRequestBody(matchingJsonPath("$[?(@.temperature == 0)]"))
                .withRequestBody(matchingJsonPath("$[?(@.top_p == 1)]"))
                .withRequestBody(matchingJsonPath("$[?(@.presence_penalty == 0)]"))
                .withRequestBody(matchingJsonPath("$[?(@.frequency_penalty == 0)]")));
        assertThat(tokenZaehler.stand()).isEqualTo(new TokenZaehler.Stand(11, 7, 1, 0));
    }

    @Test
    void http502OhneWiederholung() {
        server.stubFor(post(urlEqualTo(PFAD)).willReturn(aResponse().withStatus(502)
                .withBody("{\"result\":\"error\",\"error\":{\"code\":\"502\",\"message\":\"Bad Gateway\"}}")));

        assertThatThrownBy(() -> extraktor.extrahiere("System", Prompt.qr(Optional.empty()), "Text"));

        server.verify(1, postRequestedFor(urlEqualTo(PFAD)));
        assertThat(tokenZaehler.stand().fehler()).isEqualTo(1);
    }

    @Test
    void hostpruefungPrueftDenVerwendetenSchluessel() {
        assertThat(Hostpruefung.SCHLUESSEL).contains(KiModell.BASIS_URL);
    }
}
