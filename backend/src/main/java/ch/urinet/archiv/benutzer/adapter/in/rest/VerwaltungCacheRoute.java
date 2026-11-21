package ch.urinet.archiv.benutzer.adapter.in.rest;

import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.util.Arrays;
import java.util.List;

@ApplicationScoped
public class VerwaltungCacheRoute {

    private final List<String> verwaltung;

    @Inject
    public VerwaltungCacheRoute(Config config) {
        this(config.getValue("quarkus.rest.path", String.class));
    }

    VerwaltungCacheRoute(String restPfad) {
        String basis = restPfad.endsWith("/") ? restPfad.substring(0, restPfad.length() - 1) : restPfad;
        verwaltung = List.of(basis + "/rollen", basis + "/benutzer", basis + "/mandant/einstellungen",
                basis + "/wissensbasis/einstellungen", basis + "/konfiguration/versionen");
    }

    void einrichten(@Observes Router router) {
        // Vor CORS (-300) und Anmeldung (-200), damit auch deren Antworten no-store tragen.
        router.route().order(-350).handler(this::behandeln);
    }

    void behandeln(RoutingContext kontext) {
        String pfad = kontext.normalizedPath().replaceAll(";[^/]*", "").replaceAll("/{2,}", "/");
        if (verwaltung.stream().anyMatch(p -> pfad.equals(p) || pfad.startsWith(p + "/"))) {
            kontext.addHeadersEndHandler(ignoriert -> {
                boolean vorhanden = kontext.response().headers().getAll(HttpHeaders.CACHE_CONTROL).stream()
                        .flatMap(wert -> Arrays.stream(wert.split(",")))
                        .anyMatch(wert -> wert.trim().equalsIgnoreCase("no-store"));
                if (!vorhanden) {
                    kontext.response().headers().add(HttpHeaders.CACHE_CONTROL, "no-store");
                }
            });
        }
        kontext.next();
    }
}
