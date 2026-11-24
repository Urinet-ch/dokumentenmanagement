package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class SicherheitsHeaderRoute {

    void einrichten(@Observes Router router) {
        router.route().order(-400).handler(this::behandeln);
    }

    void behandeln(RoutingContext kontext) {
        kontext.addHeadersEndHandler(ignoriert -> {
            kontext.response().putHeader("Content-Security-Policy", "frame-ancestors 'none'");
            kontext.response().putHeader("X-Frame-Options", "DENY");
        });
        kontext.next();
    }
}
