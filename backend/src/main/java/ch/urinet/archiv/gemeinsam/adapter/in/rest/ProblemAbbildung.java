package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import com.fasterxml.jackson.core.JacksonException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.AuthenticationRedirectException;
import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Übersetzt jede Ausnahme einer Resource in Problem Details (RFC 9457, design.md «Error Handling»). Fachfehler tragen
 * ihren {@link Fehlercode}; Ausnahmen des Frameworks werden nach Status abgebildet; alles andere wird 500
 * {@code INTERNER_FEHLER} ohne Meldung, Klassenname oder Stacktrace (Req 16.7). 403 aus {@code @RolesAllowed} bildet
 * {@code sicherheit} ab, weil es die Verweigerung protokolliert (Req 10.7).
 */
public class ProblemAbbildung {

    static final String BEARER = "Bearer";

    @ServerExceptionMapper
    public Response fachFehler(FachFehler fehler) {
        return antwort(ProblemAntwort.fuer(fehler.code()));
    }

    @ServerExceptionMapper({UnauthorizedException.class, AuthenticationFailedException.class})
    public Response nichtAngemeldet(RuntimeException fehler) {
        return nichtAngemeldet();
    }

    @ServerExceptionMapper
    public Response umleitung(AuthenticationRedirectException fehler) {
        return nichtAngemeldet();
    }

    @ServerExceptionMapper
    public Response json(JacksonException fehler) {
        return antwort(ProblemAntwort.fuer(Fehlercode.ANFRAGE_UNGUELTIG));
    }

    @ServerExceptionMapper
    public Response framework(WebApplicationException fehler) {
        Response ursprung = fehler.getResponse();
        int status = ursprung == null ? 500 : ursprung.getStatus();
        Response.ResponseBuilder antwort = switch (status) {
            case 400 -> builder(ProblemAntwort.fuer(Fehlercode.ANFRAGE_UNGUELTIG));
            case 401 -> nichtAngemeldetBuilder();
            case 403 -> builder(ProblemAntwort.fuer(Fehlercode.NICHT_ERLAUBT));
            case 404 -> builder(ProblemAntwort.fuer(Fehlercode.NICHT_GEFUNDEN));
            case 413 -> builder(ProblemAntwort.fuer(Fehlercode.DATEI_ZU_GROSS));
            case 415 -> builder(ProblemAntwort.fuer(Fehlercode.MEDIENTYP_FALSCH));
            default -> status >= 400 && status < 500
                    ? builder(ProblemAntwort.fuer(Fehlercode.ANFRAGE_UNGUELTIG, status))
                    : builder(ProblemAntwort.fuer(Fehlercode.INTERNER_FEHLER));
        };
        if (status == 405 && ursprung.getHeaderString(HttpHeaders.ALLOW) != null) {
            antwort.header(HttpHeaders.ALLOW, ursprung.getHeaderString(HttpHeaders.ALLOW));
        }
        return antwort.build();
    }

    @ServerExceptionMapper
    public Response unerwartet(Throwable fehler) {
        return antwort(ProblemAntwort.fuer(Fehlercode.INTERNER_FEHLER));
    }

    static Response nichtAngemeldet() {
        return nichtAngemeldetBuilder().build();
    }

    private static Response.ResponseBuilder nichtAngemeldetBuilder() {
        return builder(ProblemAntwort.fuer(Fehlercode.NICHT_ANGEMELDET)).header("WWW-Authenticate", BEARER);
    }

    public static Response antwort(ProblemAntwort problem) {
        return builder(problem).build();
    }

    private static Response.ResponseBuilder builder(ProblemAntwort problem) {
        return Response.status(problem.status()).type(ProblemAntwort.MEDIENTYP).entity(problem.json());
    }
}
