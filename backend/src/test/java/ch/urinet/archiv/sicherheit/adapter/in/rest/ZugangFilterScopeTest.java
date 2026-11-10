package ch.urinet.archiv.sicherheit.adapter.in.rest;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantAusAnmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.ErlaubtScope;
import ch.urinet.archiv.sicherheit.application.TokenIdentitaet;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Zweite Absicherung für API-Tokens (Req 2.5): Selbst wenn {@code @RolesAllowed} die Token-Rolle zulässt, lässt der
 * {@link ZugangFilter} ein Token nur an eine Methode mit {@link ErlaubtScope} genau seines Scopes. Ohne Mockito: Proxys.
 */
class ZugangFilterScopeTest {

    private final List<Optional<Aktion>> verweigert = new ArrayList<>();
    private final ZugangFilter filter = new ZugangFilter(tokenIdentitaet(ApiTokenScope.GENERATOR),
            new MandantAufloesung(null, () -> new MandantAusAnmeldung.Gueltig(new MandantCode("urinet")),
                    code -> Optional.of("m_urinet")),
            new Berechtigung((benutzer, id) -> true,
                    (benutzerId, rolle, aktion, dokumentId, grund) -> verweigert.add(aktion)));

    static class Endpunkte {
        @ErfordertAktion(Aktion.A1)
        @ErlaubtScope(ApiTokenScope.GENERATOR)
        void generator() {
        }

        @ErfordertAktion(Aktion.A1)
        @ErlaubtScope(ApiTokenScope.IMPORT)
        void nurImport() {
        }

        @ErfordertAktion(Aktion.A1)
        void ohneScope() {
        }
    }

    @Test
    void tokenErreichtDieMethodeSeinesScopes() {
        filter.pruefen(ressource("generator"), anfrage());

        assertThat(verweigert).isEmpty();
    }

    @Test
    void tokenOhnePassendenScopeErhaelt403() {
        for (String methode : List.of("ohneScope", "nurImport")) {
            assertThatThrownBy(() -> filter.pruefen(ressource(methode), anfrage())).as(methode)
                    .isInstanceOfSatisfying(Verweigerung.class,
                            v -> assertThat(v.grund()).isEqualTo(Verweigerung.Grund.NICHT_ERLAUBT));
        }
        assertThat(verweigert).containsExactly(Optional.of(Aktion.A1), Optional.of(Aktion.A1));
    }

    private static ResourceInfo ressource(String name) {
        Method methode;
        try {
            methode = Endpunkte.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        return proxy(ResourceInfo.class, (m, args) -> m.equals("getResourceMethod") ? methode : null);
    }

    private static ContainerRequestContext anfrage() {
        return proxy(ContainerRequestContext.class, (m, args) -> null);
    }

    private static SecurityIdentity tokenIdentitaet(ApiTokenScope scope) {
        Principal principal = () -> "api-token:abcdefghijkl";
        return proxy(SecurityIdentity.class, (m, args) -> switch (m) {
            case "isAnonymous" -> false;
            case "getPrincipal" -> principal;
            case "getAttribute" -> TokenIdentitaet.SCOPE.equals(args[0]) ? scope : null;
            case "getRoles" -> java.util.Set.of(scope == ApiTokenScope.GENERATOR
                    ? ApiTokenScope.ROLLE_GENERATOR : ApiTokenScope.ROLLE_IMPORT);
            default -> null;
        });
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
