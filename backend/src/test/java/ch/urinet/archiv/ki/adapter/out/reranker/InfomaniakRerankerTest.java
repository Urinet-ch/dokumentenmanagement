package ch.urinet.archiv.ki.adapter.out.reranker;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.ki.adapter.out.langchain4j.TestModelle;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.RerankerPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST-Adapter des Rerankers gegen WireMock (Spec 02, Aufgabe 6.4; Spike 0.3, Abschnitt 6): Form des Requests,
 * Bearer-Token aus der Secret-Datei, {@code usage.prompt_tokens}, ungültige Antworten, HTTP-Fehler und
 * Zeitüberschreitung als Ausfall mit Klassennamen, nichts im Log; ohne Token kein Request.
 */
class InfomaniakRerankerTest {

    private static final String PFAD = "/2/ai/1/cohere/v2/rerank";
    private static final String MODELL = "BAAI/bge-reranker-v2-m3";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> TEXTE = List.of("Police UVG Zebrafink", "Offerte Garage", "Lohn März");

    private WireMockServer server;
    private LogMitschnitt log;

    @BeforeEach
    void starten() {
        server = new WireMockServer(options().dynamicPort().bindAddress("localhost"));
        server.start();
        log = LogMitschnitt.starten();
    }

    @AfterEach
    void stoppen() {
        log.close();
        server.stop();
    }

    @Test
    void requestNachSpikeMitTokenUndAntwortInDerReihenfolgeDesModells() throws Exception {
        server.stubFor(post(urlPathEqualTo(PFAD)).willReturn(okJson("""
                {"id":"score-1","model":"BAAI/bge-reranker-v2-m3","usage":{"prompt_tokens":253,"total_tokens":253},
                 "results":[{"index":2,"document":{"text":"x"},"relevance_score":0.9},
                            {"index":0,"document":{"text":"y"},"relevance_score":0.346}]}""")));

        RerankerPort.Antwort antwort = reranker(Optional.of(" geheim\n"), Duration.ofSeconds(2))
                .sortieren("Unfallversicherung", TEXTE, 2);

        assertThat(antwort).isEqualTo(new RerankerPort.Sortiert(List.of(2, 0), List.of(0.9, 0.346),
                new KiPort.Verbrauch(MODELL, 253, 0)));
        List<LoggedRequest> anfragen = server.findAll(postRequestedFor(urlPathEqualTo(PFAD)));
        assertThat(anfragen).hasSize(1);
        assertThat(anfragen.getFirst().getHeader("Authorization")).isEqualTo("Bearer geheim");
        JsonNode koerper = JSON.readTree(anfragen.getFirst().getBodyAsString());
        assertThat(koerper.path("model").asText()).isEqualTo(MODELL);
        assertThat(koerper.path("query").asText()).isEqualTo("Unfallversicherung");
        assertThat(koerper.path("documents")).hasSize(3);
        assertThat(koerper.path("documents").get(0).asText()).isEqualTo(TEXTE.getFirst());
        assertThat(koerper.path("top_n").asInt()).isEqualTo(2);
        assertThat(log.volltext()).doesNotContain("Zebrafink", "Unfallversicherung", "geheim");
    }

    @Test
    void ungueltigeAntwortenSindEinAusfallMitDenGemeldetenTokens() {
        String[] antworten = {
                "{\"usage\":{\"prompt_tokens\":9},\"results\":[{\"index\":3,\"relevance_score\":0.1},"
                        + "{\"index\":0,\"relevance_score\":0.1}]}",
                "{\"usage\":{\"prompt_tokens\":9},\"results\":[{\"index\":1,\"relevance_score\":0.1},"
                        + "{\"index\":1,\"relevance_score\":0.1}]}",
                "{\"usage\":{\"prompt_tokens\":9},\"results\":[{\"index\":1,\"relevance_score\":0.1}]}",
                "{\"usage\":{\"prompt_tokens\":9},\"results\":[{\"index\":\"1\",\"relevance_score\":0.1},"
                        + "{\"index\":0,\"relevance_score\":0.1}]}",
                "{\"usage\":{\"prompt_tokens\":9}}"};
        for (String koerper : antworten) {
            server.resetAll();
            server.stubFor(post(urlPathEqualTo(PFAD)).willReturn(okJson(koerper)));
            RerankerPort.Antwort antwort = reranker(Optional.of("geheim"), Duration.ofSeconds(2))
                    .sortieren("Frage", TEXTE, 2);
            assertThat(antwort).as(koerper).isInstanceOfSatisfying(RerankerPort.Ausfall.class,
                    a -> assertThat(a.verbrauch()).isEqualTo(new KiPort.Verbrauch(MODELL, 9, 0)));
        }
    }

    @Test
    void httpFehlerUndZeitueberschreitungSindEinAusfallOhneInhalt() {
        server.stubFor(post(urlPathEqualTo(PFAD)).willReturn(aResponse().withStatus(500)
                .withBody("{\"error\":\"Police UVG Zebrafink\"}")));
        RerankerPort.Antwort fehler = reranker(Optional.of("geheim"), Duration.ofSeconds(2))
                .sortieren("Frage", TEXTE, 2);
        assertThat(fehler).isEqualTo(new RerankerPort.Ausfall("HTTP-Status 500", new KiPort.Verbrauch(MODELL, 0, 0)));

        server.resetAll();
        server.stubFor(post(urlPathEqualTo(PFAD)).willReturn(okJson("{\"results\":[]}").withFixedDelay(1500)));
        long start = System.nanoTime();
        RerankerPort.Antwort zeit = reranker(Optional.of("geheim"), Duration.ofMillis(300))
                .sortieren("Frage", TEXTE, 2);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1400));
        assertThat(zeit).isInstanceOfSatisfying(RerankerPort.Ausfall.class, a -> {
            assertThat(a.grund()).isEqualTo("HttpTimeoutException");
            assertThat(a.verbrauch().tokensEin()).isZero();
        });
        assertThat(log.volltext()).doesNotContain("Zebrafink", "Frage");
    }

    @Test
    void ohneTokenKeinRequest() {
        RerankerPort.Antwort antwort = new InfomaniakReranker(
                TestModelle.embedding("https://api.infomaniak.com/2/ai/1/openai/v1", Optional.empty()), JSON,
                Optional.of("https://api.infomaniak.com/2/ai/1/cohere/v2/rerank"), MODELL, Duration.ofSeconds(1))
                .sortieren("Frage", TEXTE, 2);
        assertThat(antwort).isInstanceOf(RerankerPort.OhneZugang.class);
        RerankerPort.Antwort ohneUrl = new InfomaniakReranker(TestModelle.embedding("http://localhost:1/v1",
                Optional.of("geheim")), JSON, Optional.empty(), MODELL, Duration.ofSeconds(1))
                .sortieren("Frage", TEXTE, 2);
        assertThat(ohneUrl).isInstanceOf(RerankerPort.OhneZugang.class);
        assertThat(server.findAll(postRequestedFor(urlPathEqualTo(PFAD)))).isEmpty();
    }

    private InfomaniakReranker reranker(Optional<String> token, Duration zeitgrenze) {
        String basis = "http://localhost:" + server.port();
        return new InfomaniakReranker(TestModelle.embedding(basis + "/2/ai/1/openai/v1", token), JSON,
                Optional.of(basis + PFAD), MODELL, zeitgrenze);
    }
}
