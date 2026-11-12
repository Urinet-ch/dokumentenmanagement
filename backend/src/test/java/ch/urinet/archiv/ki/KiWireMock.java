package ch.urinet.archiv.ki;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Sprachmodell für alle Tests (Aufgabe 3.2, Req 16.7, 16.21): WireMock an localhost mit freiem Port, gesetzt als
 * {@code archiv.ki.wiremock-port}; KI-Aufrufe gehen nie an echte Modelle. Ohne Antwort-Stub antwortet WireMock mit 404,
 * der KI-Vorschlag fällt aus und das Dokument geht ohne Vorschlag in den Eingangskorb (Req 7.3). Tests steuern ihn über
 * die Admin-Schnittstelle ({@link #client()}), weil Ressource und Test in verschiedenen Classloadern laufen.
 */
@WithTestResource(value = KiWireMock.class, scope = TestResourceScope.GLOBAL)
public final class KiWireMock implements QuarkusTestResourceLifecycleManager {

    private WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(options().dynamicPort().bindAddress("localhost"));
        server.start();
        return Map.of("archiv.ki.wiremock-port", String.valueOf(server.port()));
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    /** Admin-Client für den WireMock der laufenden Anwendung. */
    public static WireMock client() {
        int port = ConfigProvider.getConfig().getValue("archiv.ki.wiremock-port", Integer.class);
        return new WireMock("localhost", port);
    }
}
