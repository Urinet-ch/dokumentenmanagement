package ch.urinet.archiv.sicherheit.adapter.in.auth;

import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.quarkus.security.identity.request.BaseAuthenticationRequest;

import java.util.Optional;

/** Anmeldung mit einem gelesenen API-Token; nur {@link ApiTokenIdentityProvider} verarbeitet sie. */
public final class ApiTokenAnfrage extends BaseAuthenticationRequest {

    private final ApiToken token;
    private final Optional<String> absenderIp;

    public ApiTokenAnfrage(ApiToken token) {
        this(token, Optional.empty());
    }

    public ApiTokenAnfrage(ApiToken token, Optional<String> absenderIp) {
        this.token = token;
        this.absenderIp = absenderIp;
    }

    public ApiToken token() {
        return token;
    }

    public Optional<String> absenderIp() {
        return absenderIp;
    }

    @Override
    public String toString() {
        return "ApiTokenAnfrage[" + token + "]";
    }
}
