package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import io.quarkus.oidc.OIDCException;
import io.quarkus.security.AuthenticationCompletionException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.vertx.http.runtime.RouteConstants;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Problem Details auch für Fehler vor der Resource (RFC 9457): Die Anmeldung und die HTTP-Rechte unter {@code /api/*}
 * scheitern in Vert.x, bevor eine {@code ServerExceptionMapper} greift. 401 mit {@code WWW-Authenticate: Bearer}, 403
 * {@code NICHT_ERLAUBT}; Keycloak nicht erreichbar ({@code OIDCException}) gilt als nicht angemeldet. Andere Fehler gehen
 * an den nächsten Handler.
 * <p>
 * Die Grenze der Anfrage ({@code quarkus.http.limits.max-body-size}) prüft Quarkus vor allen Handlern und antwortet mit
 * einer leeren 413. Darum prüft {@link #grenzePruefen} sie für {@code /api/*} kurz davor am {@code Content-Length} und
 * antwortet mit Problem Details. Seit Aufgabe 2.4 ist die Grenze von Quarkus die für Uploads ({@code multipart/form-data},
 * {@code archiv.upload.max-anfrage}); alle anderen Anfragen prüft sie gegen die kleinere {@code archiv.http.max-anfrage}; die Verbindung wird danach wie bei Quarkus geschlossen, weil der Rest der Anfrage nicht
 * gelesen wird. Eine Anfrage ohne {@code Content-Length} (chunked), die erst beim Lesen zu gross wird, beantwortet
 * Quarkus weiter selbst mit 413 ohne Inhalt.
 * <p>
 * {@code UEBERLASTET} aus der Prüfung eines API-Tokens (Req 2.5) entsteht bei der proaktiven Anmeldung auf jedem Pfad,
 * auch unter {@code /q/}; {@link #ueberlastet} antwortet darum auf allen Pfaden mit 503, {@code Retry-After} und Problem
 * Details.
 */
@ApplicationScoped
public class ProblemFehlerRoute {

    static final String API = "/api/*";
    static final String NOCHMALS_NACH_SEKUNDEN = "1";

    static final String MULTIPART = "multipart/form-data";

    private final Optional<MemorySize> grenze;
    private final MemorySize grenzeOhneUpload;

    public ProblemFehlerRoute(@ConfigProperty(name = "quarkus.http.limits.max-body-size") Optional<MemorySize> grenze,
                              @ConfigProperty(name = "archiv.http.max-anfrage") MemorySize grenzeOhneUpload) {
        this.grenze = grenze;
        this.grenzeOhneUpload = grenzeOhneUpload;
    }

    void einrichten(@Observes Router router) {
        router.route(API).order(RouteConstants.ROUTE_ORDER_UPLOAD_LIMIT - 1).handler(this::grenzePruefen);
        router.route().order(-2).failureHandler(this::ueberlastet);
        router.route(API).order(-1).failureHandler(this::behandeln);
    }

    void ueberlastet(RoutingContext kontext) {
        if (kontext.failure() instanceof FachFehler fehler && fehler.code() == Fehlercode.UEBERLASTET
                && !kontext.response().headWritten()) {
            kontext.response().putHeader("Retry-After", NOCHMALS_NACH_SEKUNDEN);
            senden(kontext, ProblemAntwort.fuer(Fehlercode.UEBERLASTET), false);
            return;
        }
        kontext.next();
    }

    void grenzePruefen(RoutingContext kontext) {
        String laenge = kontext.request().getHeader(HttpHeaders.CONTENT_LENGTH);
        if (laenge != null && zuGross(laenge, grenze(kontext.request().getHeader(HttpHeaders.CONTENT_TYPE)))) {
            kontext.response().putHeader(HttpHeaders.CONNECTION, "close")
                    .endHandler(ende -> kontext.request().connection().close());
            senden(kontext, ProblemAntwort.fuer(Fehlercode.DATEI_ZU_GROSS), false);
            return;
        }
        kontext.next();
    }

    /** Grenze je Art der Anfrage; die kleinere Grenze gilt für alles ausser {@code multipart/form-data}. */
    long grenze(String inhaltstyp) {
        long gesamt = grenze.map(MemorySize::asLongValue).orElse(Long.MAX_VALUE);
        boolean upload = inhaltstyp != null && inhaltstyp.strip().toLowerCase(java.util.Locale.ROOT).startsWith(MULTIPART);
        return upload ? gesamt : Math.min(gesamt, grenzeOhneUpload.asLongValue());
    }

    private static boolean zuGross(String laenge, long grenze) {
        try {
            return Long.parseLong(laenge.trim()) > grenze;
        } catch (NumberFormatException e) {
            // ungültige Länge prüft Quarkus selbst
            return false;
        }
    }

    void behandeln(RoutingContext kontext) {
        Throwable fehler = kontext.failure();
        if (kontext.response().headWritten()) {
            kontext.next();
            return;
        }
        if (fehler instanceof AuthenticationFailedException || fehler instanceof UnauthorizedException
                || fehler instanceof AuthenticationCompletionException || fehler instanceof OIDCException) {
            senden(kontext, ProblemAntwort.fuer(Fehlercode.NICHT_ANGEMELDET, fehler), true);
        } else if (fehler instanceof ForbiddenException) {
            senden(kontext, ProblemAntwort.fuer(Fehlercode.NICHT_ERLAUBT), false);
        } else {
            kontext.next();
        }
    }

    private static void senden(RoutingContext kontext, ProblemAntwort problem, boolean anmelden) {
        if (anmelden) {
            kontext.response().putHeader("WWW-Authenticate", ProblemAbbildung.BEARER);
        }
        kontext.response().setStatusCode(problem.status())
                .putHeader(HttpHeaders.CONTENT_TYPE, ProblemAntwort.MEDIENTYP)
                .end(problem.json());
    }
}
