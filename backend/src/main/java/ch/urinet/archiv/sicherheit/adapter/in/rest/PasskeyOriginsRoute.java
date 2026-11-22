package ch.urinet.archiv.sicherheit.adapter.in.rest;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

@ApplicationScoped
public class PasskeyOriginsRoute {
    private static final String PFAD = "/.well-known/webauthn";
    private final Optional<String> keycloakUrl;

    public PasskeyOriginsRoute(@ConfigProperty(name = "quarkus.oidc.auth-server-url") Optional<String> keycloakUrl) {
        this.keycloakUrl = keycloakUrl;
    }

    void einrichten(@Observes Router router) {
        router.get(PFAD).order(-1).handler(kontext -> {
            Optional<String> origin = keycloakUrl.flatMap(PasskeyOriginsRoute::origin);
            if (origin.isEmpty()) {
                kontext.response().setStatusCode(503).putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                        .end("Keycloak-Origin fehlt oder ist ungültig.");
                return;
            }
            kontext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                    .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                    .end(new JsonObject().put("origins", new JsonArray().add(origin.get())).encode());
        });
    }

    static Optional<String> origin(String adresse) {
        try {
            URI uri = new URI(adresse);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() > 65535) {
                return Optional.empty();
            }
            int port = uri.getPort();
            if (("https".equals(uri.getScheme()) && port == 443) || ("http".equals(uri.getScheme()) && port == 80)) {
                port = -1;
            }
            return Optional.of(new URI(uri.getScheme(), null, uri.getHost(), port, null, null, null).toString());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
