package ch.urinet.archiv.sicherheit.adapter.in.auth;

import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.Set;

/**
 * Anmeldung mit API-Token (Req 2.5): {@code Authorization: Bearer ua_<id>_<geheimnis>}. Jedes andere Bearer-Token geht
 * an OIDC weiter. Beginnt das Token mit {@code ua_} (Gross-/Kleinschreibung egal, z. B. {@code UA_}), aber der Aufbau
 * stimmt nicht, antwortet das Archiv 401 ohne Rückfall auf OIDC. Die Prüfung selbst ({@link ApiTokenIdentityProvider})
 * läuft auf einem Worker-Thread. Priorität zwischen {@code ProblemAnmeldung} (Challenge) und OIDC (1000), damit ein
 * {@code ua_}-Token nie bei OIDC landet.
 */
@ApplicationScoped
public class ApiTokenAuthMechanism implements HttpAuthenticationMechanism {

    static final int PRIORITAET = 2000;
    private static final String BEARER = "bearer ";

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext kontext, IdentityProviderManager anbieter) {
        Optional<String> wert = bearer(kontext.request().headers().get(HttpHeaders.AUTHORIZATION));
        if (wert.isEmpty() || !istApiToken(wert.get())) {
            return Uni.createFrom().nullItem();
        }
        Optional<ApiToken> token = ApiToken.lesen(wert.get());
        if (token.isEmpty()) {
            return Uni.createFrom().failure(new AuthenticationFailedException("API-Token ungültig"));
        }
        ApiTokenAnfrage anfrage = new ApiTokenAnfrage(token.get());
        HttpSecurityUtils.setRoutingContextAttribute(anfrage, kontext);
        return anbieter.authenticate(anfrage);
    }

    /** Wert nach {@code Bearer }, Gross-/Kleinschreibung des Schemas egal; leer ohne Bearer-Header. */
    private static Optional<String> bearer(String kopf) {
        if (kopf == null || kopf.length() <= BEARER.length() || !kopf.regionMatches(true, 0, BEARER, 0,
                BEARER.length())) {
            return Optional.empty();
        }
        return Optional.of(kopf.substring(BEARER.length()).strip());
    }

    /** Ein Wert mit Präfix {@code ua_} in beliebiger Schreibweise gehört dem Archiv und geht nie an OIDC. */
    private static boolean istApiToken(String wert) {
        return wert.regionMatches(true, 0, ApiToken.PRAEFIX, 0, ApiToken.PRAEFIX.length());
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext kontext) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(ApiTokenAnfrage.class);
    }

    @Override
    public int getPriority() {
        return PRIORITAET;
    }
}
