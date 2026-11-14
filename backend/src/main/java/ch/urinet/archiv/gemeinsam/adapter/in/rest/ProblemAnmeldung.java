package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

/**
 * Fehlt die Anmeldung unter {@code /api/}, beendet Quarkus die Anfrage über die Challenge mit leerem Körper statt über
 * einen Fehler. Dieser Mechanismus meldet nie selbst an (das macht OIDC), schreibt aber die Challenge vor OIDC als
 * Problem Details {@code NICHT_ANGEMELDET} mit {@code WWW-Authenticate: Bearer}. Andere Pfade gehen an OIDC.
 * <p>
 * Ebenso unter {@code /public/} (Spec 03, Req 15.3, Aufgabe 11.6): Anfragen dorthin kommen nach dem Reroute auf den
 * internen Pfad unter {@code /api/} an ({@code WebseitenRoute}); der eigene Präfix gilt, falls eine Challenge doch vor
 * dem Reroute entsteht.
 */
@ApplicationScoped
public class ProblemAnmeldung implements HttpAuthenticationMechanism {

    static final String API = "/api/";
    static final String WEBSEITE = "/public/";

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext kontext, IdentityProviderManager anbieter) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext kontext) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public Uni<Boolean> sendChallenge(RoutingContext kontext) {
        String pfad = kontext.normalizedPath();
        if (!(pfad.startsWith(API) || pfad.startsWith(WEBSEITE)) || kontext.response().headWritten()) {
            return Uni.createFrom().item(false);
        }
        ProblemAntwort problem = ProblemAntwort.fuer(Fehlercode.NICHT_ANGEMELDET);
        kontext.response().setStatusCode(problem.status())
                .putHeader("WWW-Authenticate", ProblemAbbildung.BEARER)
                .putHeader(HttpHeaders.CONTENT_TYPE, ProblemAntwort.MEDIENTYP)
                .end(problem.json());
        return Uni.createFrom().item(true);
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of();
    }

    @Override
    public int getPriority() {
        return Integer.MAX_VALUE;
    }
}
