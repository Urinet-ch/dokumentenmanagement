package ch.urinet.archiv.protokoll.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.application.TokenIdentitaet;
import io.quarkus.arc.Arc;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.json.JsonString;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Optional;

/**
 * Name der Kennung aus der laufenden Anmeldung (Req 10.10): bei Keycloak der Claim {@code name}, sonst
 * {@code preferred_username}; bei einem API-Token die Bezeichnung aus {@code public.api_client}. Nur wenn die Anmeldung
 * zur Kennung des Eintrags gehört; ausserhalb einer Anfrage (z. B. Worker) leer.
 */
@ApplicationScoped
class NameAusAnmeldung {

    private final Instance<SecurityIdentity> identitaet;

    NameAusAnmeldung(Instance<SecurityIdentity> identitaet) {
        this.identitaet = identitaet;
    }

    Optional<String> name(String benutzer) {
        if (!Arc.container().requestContext().isActive()) {
            return Optional.empty();
        }
        SecurityIdentity id = identitaet.get();
        if (id == null || id.isAnonymous() || id.getPrincipal() == null
                || !benutzer.equals(id.getPrincipal().getName())) {
            return Optional.empty();
        }
        Optional<String> token = text(id.getAttribute(TokenIdentitaet.NAME));
        if (token.isPresent() || !(id.getPrincipal() instanceof JsonWebToken jwt)) {
            return token;
        }
        return text(jwt.getClaim("name")).or(() -> text(jwt.getClaim("preferred_username")));
    }

    private static Optional<String> text(Object wert) {
        String text = switch (wert) {
            case String s -> s;
            case JsonString js -> js.getString();
            case null, default -> null;
        };
        return Optional.ofNullable(text).map(String::strip).filter(t -> !t.isEmpty());
    }
}
