package ch.urinet.archiv.sicherheit.adapter.in.rest;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantAusAnmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.ErlaubtScope;
import ch.urinet.archiv.sicherheit.application.TokenWiderruf;
import ch.urinet.archiv.sicherheit.application.TokenIdentitaet;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.time.Instant;
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
                    (benutzerId, rolle, aktion, dokumentId, grund) -> verweigert.add(aktion)),
            new TokenWiderruf() {
                @Override
                public boolean widerrufen(String benutzer, Instant ausgestellt) {
                    return false;
                }

                @Override
                public void setzen(String benutzer, Instant ab) {
                }

                @Override
                public int aufraeumen(Instant vor) {
                    return 0;
                }
            });

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

    @Test
    void widerrufenesBenutzertokenErhaelt401VorDerAktion() {
        TokenWiderruf widerruf = new TokenWiderruf() {
            @Override
            public boolean widerrufen(String benutzer, Instant ausgestellt) {
                return benutzer.equals("benutzer-1") && ausgestellt.equals(Instant.ofEpochSecond(1));
            }

            @Override
            public void setzen(String benutzer, Instant ab) {
            }

            @Override
            public int aufraeumen(Instant vor) {
                return 0;
            }
        };
        var filter = new ZugangFilter(benutzerIdentitaet(), new MandantAufloesung(null,
                () -> new MandantAusAnmeldung.Gueltig(new MandantCode("urinet")), code -> Optional.of("m_urinet")),
                new Berechtigung((benutzer, id) -> true,
                        (benutzerId, rolle, aktion, dokumentId, grund) -> verweigert.add(aktion)), widerruf);

        assertThatThrownBy(() -> filter.pruefen(ressource("ohneScope"), anfrage()))
                .isInstanceOfSatisfying(FachFehler.class, fehler ->
                        assertThat(fehler.code()).isEqualTo(Fehlercode.NICHT_ANGEMELDET));
        assertThat(verweigert).isEmpty();
    }

    @Test
    void nachDemWiderrufAusgestelltesTokenBleibtGueltig() {
        Instant ab = Instant.ofEpochSecond(2);
        TokenWiderruf widerruf = new TokenWiderruf() {
            @Override
            public boolean widerrufen(String benutzer, Instant ausgestellt) {
                return ausgestellt.isBefore(ab);
            }

            @Override
            public void setzen(String benutzer, Instant zeit) {
            }

            @Override
            public int aufraeumen(Instant vor) {
                return 0;
            }
        };
        var filter = new ZugangFilter(benutzerIdentitaet(3), new MandantAufloesung(null,
                () -> new MandantAusAnmeldung.Gueltig(new MandantCode("urinet")), code -> Optional.of("m_urinet")),
                new Berechtigung((benutzer, id) -> true,
                        (benutzerId, rolle, aktion, dokumentId, grund) -> verweigert.add(aktion)), widerruf);

        filter.pruefen(ressource("ohneScope"), anfrage());

        assertThat(verweigert).isEmpty();
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
        return proxy(ContainerRequestContext.class, (m, args) -> m.equals("getUriInfo")
                ? proxy(UriInfo.class, (uriMethode, uriArgs) -> uriMethode.equals("getPathParameters")
                        ? new MultivaluedHashMap<String, String>() : null)
                : null);
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

    private static SecurityIdentity benutzerIdentitaet() {
        return benutzerIdentitaet(1);
    }

    private static SecurityIdentity benutzerIdentitaet(long ausgestellt) {
        JsonWebToken jwt = proxy(JsonWebToken.class, (m, args) -> switch (m) {
            case "getName" -> "benutzer-1";
            case "getIssuedAtTime" -> ausgestellt;
            default -> null;
        });
        return proxy(SecurityIdentity.class, (m, args) -> switch (m) {
            case "isAnonymous" -> false;
            case "getPrincipal" -> jwt;
            case "getAttribute" -> null;
            case "getRoles" -> java.util.Set.of("inhaber");
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
