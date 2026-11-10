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
 */
@ApplicationScoped
public class ProblemAnmeldung implements HttpAuthenticationMechanism {

    static final String API = "/api/";

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
        if (!kontext.normalizedPath().startsWith(API) || kontext.response().headWritten()) {
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
