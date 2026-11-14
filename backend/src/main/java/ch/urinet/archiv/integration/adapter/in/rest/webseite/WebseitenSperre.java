package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.domain.webseite.Hauptversion;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * Zweite Sperre des internen Pfads (Spec 03, Aufgabe 11.6, Review PR #140): Erreicht eine Anfrage eine Resource unter
 * {@code /public/}, ohne dass {@link WebseitenRoute} sie umgeleitet hat, antwortet sie 404 {@code NICHT_GEFUNDEN}.
 * Die Route sperrt die bekannten Wege schon vor der Anmeldung; dieser Filter prüft nach der Zuordnung den Pfad, den
 * Quarkus REST tatsächlich zugeordnet hat, damit die Grenze nicht davon abhängt, wie Quarkus den Pfad auswertet. Er läuft
 * vor {@code ZugangFilter}, damit eine solche Anfrage keine Mandanten- oder Rechteprüfung auslöst.
 */
public class WebseitenSperre {

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION)
    public void pruefen(ContainerRequestContext anfrage, RoutingContext kontext) {
        if (gesperrt(anfrage.getUriInfo().getPath(), kontext.get(WebseitenRoute.AEUSSERER_PFAD) != null)) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
    }

    /** {@code zugeordnet}: Pfad relativ zu {@code quarkus.rest.path}, mit oder ohne führenden Schrägstrich. */
    static boolean gesperrt(String zugeordnet, boolean umgeleitet) {
        String pfad = zugeordnet.startsWith("/") ? zugeordnet : "/" + zugeordnet;
        boolean webseite = pfad.equals(Hauptversion.PRAEFIX) || pfad.startsWith(Hauptversion.PRAEFIX + "/");
        return webseite && !umgeleitet;
    }
}
