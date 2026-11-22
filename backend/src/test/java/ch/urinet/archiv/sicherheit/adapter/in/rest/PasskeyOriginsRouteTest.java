package ch.urinet.archiv.sicherheit.adapter.in.rest;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PasskeyOriginsRouteTest {
    @Test
    void liefertNurDieKonfigurierteKeycloakOriginAlsJson() throws Exception {
        pruefen(Optional.of("https://auth.example.invalid:8443/realms/test"), 200,
                "{\"origins\":[\"https://auth.example.invalid:8443\"]}", "application/json");
    }

    @Test
    void fehlendeOderUngueltigeKonfigurationLiefertKeinenErfolg() throws Exception {
        pruefen(Optional.empty(), 503, "Keycloak-Origin fehlt oder ist ungültig.", null);
        pruefen(Optional.of("https://user@auth.example.invalid/realms/test"), 503,
                "Keycloak-Origin fehlt oder ist ungültig.", null);
    }

    @Test
    void standardPortGehoertNichtZurSerialisiertenOrigin() {
        assertThat(PasskeyOriginsRoute.origin("https://auth.example.invalid:443/realms/test"))
                .contains("https://auth.example.invalid");
        assertThat(PasskeyOriginsRoute.origin("http://localhost:80/realms/test"))
                .contains("http://localhost");
    }

    private void pruefen(Optional<String> url, int status, String inhalt, String contentType) throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            Router router = Router.router(vertx);
            new PasskeyOriginsRoute(url).einrichten(router);
            var server = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1")
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            URI adresse = URI.create("http://127.0.0.1:" + server.actualPort() + "/.well-known/webauthn");
            HttpResponse<String> antwort = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(adresse).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(antwort.statusCode()).isEqualTo(status);
            assertThat(antwort.body()).isEqualTo(inhalt);
            assertThat(antwort.headers().firstValue("cache-control")).contains("no-store");
            if (contentType != null) {
                assertThat(antwort.headers().firstValue("content-type")).contains(contentType);
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
