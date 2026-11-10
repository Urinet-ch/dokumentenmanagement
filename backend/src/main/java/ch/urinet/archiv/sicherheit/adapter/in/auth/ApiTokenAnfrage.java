package ch.urinet.archiv.sicherheit.adapter.in.auth;

import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.quarkus.security.identity.request.BaseAuthenticationRequest;

/** Anmeldung mit einem gelesenen API-Token; nur {@link ApiTokenIdentityProvider} verarbeitet sie. */
public final class ApiTokenAnfrage extends BaseAuthenticationRequest {

    private final ApiToken token;

    public ApiTokenAnfrage(ApiToken token) {
        this.token = token;
    }

    public ApiToken token() {
        return token;
    }

    @Override
    public String toString() {
        return "ApiTokenAnfrage[" + token + "]";
    }
}
