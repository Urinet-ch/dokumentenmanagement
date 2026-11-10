package ch.urinet.archiv.sicherheit.adapter.in.auth;

import ch.urinet.archiv.sicherheit.application.ApiTokenPruefung;
import ch.urinet.archiv.sicherheit.application.TokenIdentitaet;
import ch.urinet.archiv.sicherheit.domain.ApiClient;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Prüft das API-Token ({@link ApiTokenPruefung}, blockierend auf einem Worker-Thread) und baut die Identität: Principal
 * {@code token:<id>}, Attribute Mandant und Scope, als einzige Rolle die Scope-Rolle; keine Archiv-Rolle (Req 2.5).
 * Unbekannte ID, falsches Geheimnis oder widerrufen → 401, ohne zu sagen, was davon.
 */
@ApplicationScoped
public class ApiTokenIdentityProvider implements IdentityProvider<ApiTokenAnfrage> {

    private final ApiTokenPruefung pruefung;

    public ApiTokenIdentityProvider(ApiTokenPruefung pruefung) {
        this.pruefung = pruefung;
    }

    @Override
    public Class<ApiTokenAnfrage> getRequestType() {
        return ApiTokenAnfrage.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(ApiTokenAnfrage anfrage, AuthenticationRequestContext kontext) {
        return kontext.runBlocking(() -> pruefung.pruefen(anfrage.token()).map(ApiTokenIdentityProvider::identitaet)
                .orElseThrow(() -> new AuthenticationFailedException("API-Token abgelehnt")));
    }

    private static SecurityIdentity identitaet(ApiClient client) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(ApiToken.principal(client.id())))
                .addRole(client.scope().rolle())
                .addAttribute(TokenIdentitaet.MANDANT, client.mandant())
                .addAttribute(TokenIdentitaet.SCOPE, client.scope())
                .build();
    }
}
