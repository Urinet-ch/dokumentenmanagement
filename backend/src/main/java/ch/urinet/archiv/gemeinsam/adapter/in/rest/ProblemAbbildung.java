package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.PflichtfelderFehlen;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.quarkus.oidc.OIDCException;
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
    /** Wartezeit für {@code SPEICHER_NICHT_ERREICHBAR} (design.md «Error Handling»), Auslegung Aufgabe 2.4. */
    static final String SPEICHER_NOCHMALS_NACH_SEKUNDEN = "30";

    @ServerExceptionMapper
    public Response fachFehler(FachFehler fehler) {
        if (fehler instanceof PflichtfelderFehlen fehlen) {
            return builder(ProblemAntwort.mitErweiterung(fehler.code(), "felder", fehlen.felder())).build();
        }
        Response.ResponseBuilder antwort = builder(ProblemAntwort.fuer(fehler.code()));
        if (fehler.code() == Fehlercode.SPEICHER_NICHT_ERREICHBAR) {
            antwort.header(HttpHeaders.RETRY_AFTER, SPEICHER_NOCHMALS_NACH_SEKUNDEN);
        }
        return antwort.build();
    }

    @ServerExceptionMapper({UnauthorizedException.class, AuthenticationFailedException.class})
    public Response nichtAngemeldet(RuntimeException fehler) {
        return nichtAngemeldetBuilder(fehler).build();
    }

    /**
     * Keycloak nicht erreichbar (z. B. beim Start, solange {@code kc} noch hochfährt): Das Token lässt sich nicht
     * prüfen, also gilt es als nicht angemeldet (401 statt 500, Aufgabe 1.9). Die Ursache steht mit Stufe INFO im Log.
     */
    @ServerExceptionMapper
    public Response oidcNichtErreichbar(OIDCException fehler) {
        return nichtAngemeldetBuilder(fehler).build();
    }

    @ServerExceptionMapper
    public Response umleitung(AuthenticationRedirectException fehler) {
        return nichtAngemeldet();
    }

    /**
     * Falscher Feldtyp im JSON: Der eingebaute Mapper von Quarkus für diese Ausnahme gäbe in dev/test den Eingabewert
     * zurück und in prod einen leeren 400; dieser hier gewinnt und nennt keinen Wert (Req 16.7).
     */
    @ServerExceptionMapper
    public Response feldtyp(MismatchedInputException fehler) {
        return antwort(ProblemAntwort.fuer(Fehlercode.ANFRAGE_UNGUELTIG));
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
            case 401 -> nichtAngemeldetBuilder(fehler);
            case 403 -> builder(ProblemAntwort.fuer(Fehlercode.NICHT_ERLAUBT));
            case 404 -> builder(ProblemAntwort.fuer(Fehlercode.NICHT_GEFUNDEN));
            case 413 -> builder(ProblemAntwort.fuer(Fehlercode.DATEI_ZU_GROSS));
            case 415 -> builder(ProblemAntwort.fuer(Fehlercode.MEDIENTYP_FALSCH));
            default -> status >= 400 && status < 500
                    ? builder(ProblemAntwort.fuer(Fehlercode.ANFRAGE_UNGUELTIG, status))
                    : builder(ProblemAntwort.fuer(Fehlercode.INTERNER_FEHLER, fehler));
        };
        if (status == 405 && ursprung.getHeaderString(HttpHeaders.ALLOW) != null) {
            antwort.header(HttpHeaders.ALLOW, ursprung.getHeaderString(HttpHeaders.ALLOW));
        }
        return antwort.build();
    }

    @ServerExceptionMapper
    public Response unerwartet(Throwable fehler) {
        return antwort(ProblemAntwort.fuer(Fehlercode.INTERNER_FEHLER, fehler));
    }

    static Response nichtAngemeldet() {
        return nichtAngemeldetBuilder(null).build();
    }

    private static Response.ResponseBuilder nichtAngemeldetBuilder(Throwable ursache) {
        return builder(ProblemAntwort.fuer(Fehlercode.NICHT_ANGEMELDET, ursache)).header("WWW-Authenticate", BEARER);
    }

    public static Response antwort(ProblemAntwort problem) {
        return builder(problem).build();
    }

    private static Response.ResponseBuilder builder(ProblemAntwort problem) {
        return Response.status(problem.status()).type(ProblemAntwort.MEDIENTYP).entity(problem.json());
    }
}
