package ch.urinet.archiv.ki;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import org.eclipse.microprofile.config.ConfigProvider;

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
 */
@WithTestResource(value = KiWireMock.class, scope = TestResourceScope.GLOBAL)
public final class KiWireMock implements QuarkusTestResourceLifecycleManager {

    private WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(options().dynamicPort().bindAddress("localhost"));
        server.start();
        server.stubFor(standard());
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

    /** Setzt den WireMock der laufenden Anwendung zurück und registriert wieder den Standard-Stub. */
    public static WireMock zuruecksetzen() {
        WireMock modell = client();
        modell.resetMappings();
        modell.resetRequests();
        modell.resetScenarios();
        modell.register(standard());
        return modell;
    }

    /** Admin-Client für den WireMock der laufenden Anwendung. */
    public static WireMock client() {
        int port = ConfigProvider.getConfig().getValue("archiv.ki.wiremock-port", Integer.class);
        return new WireMock("localhost", port);
    }
}
