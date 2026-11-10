package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import io.quarkus.security.AuthenticationCompletionException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Problem Details auch für Fehler vor der Resource (RFC 9457): Die Anmeldung und die HTTP-Rechte unter {@code /api/*}
 * scheitern in Vert.x, bevor eine {@code ServerExceptionMapper} greift. 401 mit {@code WWW-Authenticate: Bearer}, 403
 * {@code NICHT_ERLAUBT}; andere Fehler gehen an den nächsten Handler.
 */
@ApplicationScoped
public class ProblemFehlerRoute {

    static final String API = "/api/*";

    void einrichten(@Observes Router router) {
        router.route(API).order(-1).failureHandler(this::behandeln);
    }

    void behandeln(RoutingContext kontext) {
        Throwable fehler = kontext.failure();
        if (kontext.response().headWritten()) {
            kontext.next();
            return;
        }
        if (fehler instanceof AuthenticationFailedException || fehler instanceof UnauthorizedException
                || fehler instanceof AuthenticationCompletionException) {
            senden(kontext, ProblemAntwort.fuer(Fehlercode.NICHT_ANGEMELDET), true);
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
