package ch.urinet.archiv.benutzer.adapter.in.rest;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class VerwaltungCacheRouteTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private Vertx vertx;

    @AfterEach
    void schliessen() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void verwaltungAntwortenUndFrueheFehlerSindNichtCachebar() throws Exception {
        vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        new VerwaltungCacheRoute("/api/v1/").einrichten(router);
        router.route().order(-300).handler(k -> {
            if (k.normalizedPath().contains("cors")) {
                k.response().setStatusCode(204).end();
            } else {
                k.next();
            }
        });
        router.route().order(-200).handler(k -> {
            if (k.normalizedPath().contains("nicht-angemeldet")) {
                k.response().setStatusCode(401).end();
            } else if (k.normalizedPath().contains("verboten")) {
                k.response().setStatusCode(403).end();
            } else if (k.normalizedPath().contains("streng")) {
                k.response().putHeader("Cache-Control", "private, no-store").end();
            } else if (k.normalizedPath().contains("lax")) {
                k.response().putHeader("Cache-Control", "no-transform, max-age=60").end();
            } else {
                k.response().end();
            }
        });
        int port = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS).actualPort();

        for (String pfad : List.of("/api/v1/rollen", "/api/v1/benutzer/nicht-angemeldet",
                "/api/v1/benutzer/verboten", "/api/v1/benutzer/cors", "/api/v1/%62enutzer",
                "/api/v1//benutzer;test/rollen", "/api/v1;x/benutzer/rollen",
                "/api/v1/mandant/einstellungen/vorschau", "/api/v1/wissensbasis/einstellungen",
                "/api/v1/konfiguration/versionen/1")) {
            assertThat(abrufen(port, pfad).headers().allValues("cache-control")).as(pfad).containsExactly("no-store");
        }
        assertThat(abrufen(port, "/api/v1/benutzerx").headers().allValues("cache-control"))
                .isEmpty();
        assertThat(abrufen(port, "/api/v1/benutzer/streng").headers().allValues("cache-control"))
                .containsExactly("private, no-store");
        assertThat(abrufen(port, "/api/v1/benutzer/lax").headers().allValues("cache-control"))
                .containsExactly("no-transform, max-age=60", "no-store");
        assertThat(abrufen(port, "/api/v1/benutzer/nicht-angemeldet").statusCode()).isEqualTo(401);
        assertThat(abrufen(port, "/api/v1/benutzer/verboten").statusCode()).isEqualTo(403);
        assertThat(abrufen(port, "/api/v1/benutzer/cors").statusCode()).isEqualTo(204);
    }

    private HttpResponse<String> abrufen(int port, String pfad) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pfad)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
