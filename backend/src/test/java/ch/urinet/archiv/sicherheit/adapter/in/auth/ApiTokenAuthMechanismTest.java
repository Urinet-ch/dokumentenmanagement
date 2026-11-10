package ch.urinet.archiv.sicherheit.adapter.in.auth;

import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Welche Bearer-Werte das Archiv selbst prüft und welche an OIDC gehen (Req 2.5). Ohne Mockito: Proxys. */
class ApiTokenAuthMechanismTest {

    private final ApiTokenAuthMechanism mechanismus = new ApiTokenAuthMechanism();
    private final List<AuthenticationRequest> anfragen = new ArrayList<>();
    private final IdentityProviderManager anbieter = proxy(IdentityProviderManager.class, (methode, args) -> {
        if (methode.equals("authenticate")) {
            anfragen.add((AuthenticationRequest) args[0]);
            return Uni.createFrom().nullItem();
        }
        return null;
    });

    @Test
    void kaputtesOderGrossgeschriebenesTokenEndetMit401OhneOidc() {
        String gross = "UA_" + ApiToken.neu(new SecureRandom()).text().substring(3);

        for (String wert : List.of("ua_kaputt", gross, "Ua_kaputt")) {
            Uni<SecurityIdentity> ergebnis = mechanismus.authenticate(kontext("Bearer " + wert), anbieter);
            assertThatThrownBy(() -> ergebnis.await().indefinitely()).as(wert)
                    .isInstanceOf(AuthenticationFailedException.class);
        }
        assertThat(anfragen).isEmpty();
    }

    @Test
    void anderesBearerTokenUndKeinHeaderGehenWeiter() {
        assertThat(mechanismus.authenticate(kontext("Bearer eyJhbGciOiJSUzI1NiJ9.e30.x"), anbieter)
                .await().indefinitely()).isNull();
        assertThat(mechanismus.authenticate(kontext(null), anbieter).await().indefinitely()).isNull();
        assertThat(mechanismus.authenticate(kontext("Basic dTpw"), anbieter).await().indefinitely()).isNull();
        assertThat(anfragen).isEmpty();
    }

    @Test
    void gueltigesTokenGehtAnDenIdentityProvider() {
        ApiToken token = ApiToken.neu(new SecureRandom());

        mechanismus.authenticate(kontext("bEaReR " + token.text()), anbieter).await().indefinitely();

        assertThat(anfragen).singleElement().isInstanceOfSatisfying(ApiTokenAnfrage.class,
                anfrage -> assertThat(anfrage.token().id()).isEqualTo(token.id()));
    }

    private static RoutingContext kontext(String authorization) {
        MultiMap kopf = MultiMap.caseInsensitiveMultiMap();
        if (authorization != null) {
            kopf.add("Authorization", authorization);
        }
        HttpServerRequest anfrage = proxy(HttpServerRequest.class,
                (methode, args) -> methode.equals("headers") ? kopf : null);
        return proxy(RoutingContext.class, (methode, args) -> methode.equals("request") ? anfrage : null);
    }

    interface Antwort {
        Object auf(String methode, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> typ, Antwort antwort) {
        return (T) Proxy.newProxyInstance(typ.getClassLoader(), new Class<?>[]{typ},
                (p, methode, args) -> antwort.auf(methode.getName(), args));
    }
}
