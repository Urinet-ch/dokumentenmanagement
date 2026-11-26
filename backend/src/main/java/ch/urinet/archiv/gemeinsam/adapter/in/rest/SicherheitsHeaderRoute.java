package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.List;

@ApplicationScoped
public class SicherheitsHeaderRoute {

    private static final String URSPRUENGLICHER_PFAD = SicherheitsHeaderRoute.class.getName() + ".pfad";

    void einrichten(@Observes Router router) {
        router.route().order(-400).handler(this::behandeln);
    }

    void behandeln(RoutingContext kontext) {
        String pfad = kontext.get(URSPRUENGLICHER_PFAD);
        if (pfad == null) {
            pfad = kontext.normalizedPath().replaceAll(";[^/]*", "").replaceAll("/{2,}", "/");
            kontext.put(URSPRUENGLICHER_PFAD, pfad);
        }
        boolean api = pfad.equals("/api") || pfad.startsWith("/api/");
        kontext.addHeadersEndHandler(ignoriert -> {
            kontext.response().putHeader("Content-Security-Policy", "frame-ancestors 'none'");
            kontext.response().putHeader("X-Frame-Options", "DENY");
            if (!kontext.response().headers().getAll("X-Content-Type-Options").equals(List.of("nosniff"))) {
                kontext.response().putHeader("X-Content-Type-Options", "nosniff");
            }
            if (api) {
                kontext.response().putHeader("Cross-Origin-Resource-Policy", "same-origin");
            }
        });
        kontext.next();
    }
}
