package ch.urinet.archiv.sicherheit.adapter.in.auth;

import ch.urinet.archiv.sicherheit.application.ApiTokenPruefung;
import ch.urinet.archiv.sicherheit.application.TokenIdentitaet;
import ch.urinet.archiv.sicherheit.application.WebseitenAbsenderPruefung;
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
import org.jboss.logging.Logger;

/**
 * Prüft das API-Token ({@link ApiTokenPruefung}, blockierend auf einem Worker-Thread) und baut die Identität: Principal
 * {@code token:<id>}, Attribute Mandant und Scope, als einzige Rolle die Scope-Rolle; keine Archiv-Rolle (Req 2.5).
 * Unbekannte ID, falsches Geheimnis oder widerrufen → 401, ohne zu sagen, was davon.
 */
@ApplicationScoped
public class ApiTokenIdentityProvider implements IdentityProvider<ApiTokenAnfrage> {

    private static final Logger LOG = Logger.getLogger(ApiTokenIdentityProvider.class);
    private final ApiTokenPruefung pruefung;
    private final WebseitenAbsenderPruefung absenderPruefung;

    public ApiTokenIdentityProvider(ApiTokenPruefung pruefung, WebseitenAbsenderPruefung absenderPruefung) {
        this.pruefung = pruefung;
        this.absenderPruefung = absenderPruefung;
    }

    @Override
    public Class<ApiTokenAnfrage> getRequestType() {
        return ApiTokenAnfrage.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(ApiTokenAnfrage anfrage, AuthenticationRequestContext kontext) {
        return kontext.runBlocking(() -> {
            ApiClient client = pruefung.pruefen(anfrage.token(), anfrage.absenderIp())
                    .orElseThrow(() -> new AuthenticationFailedException("API-Token abgelehnt"));
            if (client.absenderPruefen() && !absenderPruefung.erlaubt(client.webseite().orElseThrow(),
                    anfrage.absenderIp())) {
                LOG.warnf("Webseiten-Schlüssel wegen Absender abgelehnt: id=%s", client.id());
                throw new AuthenticationFailedException("API-Token abgelehnt");
            }
            return identitaet(client);
        });
    }

    private static SecurityIdentity identitaet(ApiClient client) {
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(ApiToken.principal(client.id())))
                .addRole(client.scope().rolle())
                .addAttribute(TokenIdentitaet.MANDANT, client.mandant())
                .addAttribute(TokenIdentitaet.SCOPE, client.scope())
                .addAttribute(TokenIdentitaet.NAME, client.name());
        client.webseite().ifPresent(webseite -> builder.addAttribute(TokenIdentitaet.WEBSEITE, webseite));
        client.hoechsteStufe().ifPresent(stufe -> builder.addAttribute(TokenIdentitaet.HOECHSTE_STUFE, stufe));
        if (client.scope().webseitenSchluessel()) {
            builder.addAttribute(TokenIdentitaet.ABSENDER_PRUEFEN, client.absenderPruefen());
            client.gueltigBis().ifPresent(bis -> builder.addAttribute(TokenIdentitaet.GUELTIG_BIS, bis));
        }
        return builder.build();
    }
}
