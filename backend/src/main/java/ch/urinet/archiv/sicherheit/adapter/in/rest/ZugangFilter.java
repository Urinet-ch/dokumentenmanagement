package ch.urinet.archiv.sicherheit.adapter.in.rest;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantVerweigert;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.ErlaubtScope;
import ch.urinet.archiv.sicherheit.application.NichtDokumentbezogen;
import ch.urinet.archiv.sicherheit.application.Oeffentlich;
import ch.urinet.archiv.sicherheit.application.TokenWiderruf;
import ch.urinet.archiv.sicherheit.application.TokenIdentitaet;
import ch.urinet.archiv.sicherheit.application.WebseitenErfassungAktion;
import ch.urinet.archiv.sicherheit.application.WebseitenZugang;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Prüft jede Anfrage an einen Endpunkt, bevor Daten gelesen werden (Req 1.3, 2.2, 2.6): gültiger, aktiver Mandant aus
 * dem Token, eine Archiv-Rolle (die stärkste gilt) und – bei {@link ErfordertAktion} – das Recht der Aktion nach der
 * Rechtematrix. Bei dokumentbezogenen Aktionen (A2–A10, A16), ausser bei {@link NichtDokumentbezogen}, mit Pfad-Parameter
 * {@code id} gilt 404 vor 403
 * ({@link Berechtigung#dokumentAktion}); {@code @RolesAllowed} lässt dort alle Archiv-Rollen zu. Ein Endpunkt ohne
 * Erklärung ({@link ErfordertAktion}, {@link AlleArchivRollen}, {@link Oeffentlich}) antwortet 403 (fail closed);
 * {@code EndpunktWaechterTest} verlangt die Erklärung schon im Build. Ohne gültigen Mandanten antwortet er 403
 * {@code MANDANT_FEHLT}; jede Verweigerung meldet {@link Berechtigung} (Zugriffsprotokoll, Req 10.7).
 * <p>
 * Ein API-Token (Attribut {@link TokenIdentitaet#SCOPE}, Req 2.5) hat keine Archiv-Rolle: Nach der Mandantenprüfung
 * zählt nur, ob die Methode mit {@link ErlaubtScope} genau seinen Scope erlaubt; Webseiten-Schlüssel verwenden
 * {@link WebseitenZugang}; sonst 403 {@code NICHT_ERLAUBT}.
 */
public class ZugangFilter {

    static final String DOKUMENT_ID = "id";

    private final SecurityIdentity identitaet;
    private final MandantAufloesung aufloesung;
    private final Berechtigung berechtigung;
    private final TokenWiderruf tokenWiderruf;

    public ZugangFilter(SecurityIdentity identitaet, MandantAufloesung aufloesung, Berechtigung berechtigung,
                        TokenWiderruf tokenWiderruf) {
        this.identitaet = identitaet;
        this.aufloesung = aufloesung;
        this.berechtigung = berechtigung;
        this.tokenWiderruf = tokenWiderruf;
    }

    @ServerRequestFilter
    public void pruefen(ResourceInfo ressource, ContainerRequestContext anfrage) {
        Method methode = ressource.getResourceMethod();
        if (methode == null || methode.isAnnotationPresent(Oeffentlich.class)) {
            return;
        }
        // Benutzer-ID = Claim sub (quarkus.oidc.token.principal-claim); fehlt er, kein Zugang
        Optional<String> benutzerId = identitaet.isAnonymous() ? Optional.empty()
                : Optional.ofNullable(identitaet.getPrincipal().getName()).filter(id -> !id.isBlank());
        try {
            aufloesung.pruefen();
        } catch (MandantVerweigert e) {
            berechtigung.ohneMandant(benutzerId);
        }
        ErfordertAktion erklaerung = methode.getAnnotation(ErfordertAktion.class);
        Optional<Aktion> aktion = Optional.ofNullable(erklaerung).map(ErfordertAktion::value);
        Object scope = identitaet.getAttribute(TokenIdentitaet.SCOPE);
        WebseitenZugang webseitenZugang = methode.getAnnotation(WebseitenZugang.class);
        if (webseitenZugang != null) {
            if (benutzerId.isEmpty() || !(scope instanceof ch.urinet.archiv.sicherheit.domain.ApiTokenScope tokenScope)
                    || !webseitenScopePasst(webseitenZugang, tokenScope)) {
                berechtigung.ohneZugang(benutzerId, Optional.empty(), aktion);
                return;
            }
            if (webseitenZugang.value() == WebseitenZugang.Art.ERFASSUNG) {
                WebseitenErfassungAktion webseitenAktion = methode.getAnnotation(WebseitenErfassungAktion.class);
                if (webseitenAktion == null) {
                    berechtigung.ohneZugang(benutzerId, Optional.of(Rolle.GESCHAEFTSSTELLE), Optional.empty());
                    return;
                }
                berechtigung.webseitenErfassung(new Benutzer(benutzerId.get(), Rolle.GESCHAEFTSSTELLE),
                        webseitenAktion.value(), methode.isAnnotationPresent(NichtDokumentbezogen.class) ? null
                                : anfrage.getUriInfo().getPathParameters().getFirst(DOKUMENT_ID));
            }
            return;
        }
        if (scope != null) {
            scopePruefen(methode, scope, benutzerId, aktion);
            return;
        }
        Optional<Rolle> rolle = Rolle.ausRealmRollen(identitaet.getRoles());
        if (rolle.isEmpty() || benutzerId.isEmpty()) {
            berechtigung.ohneZugang(benutzerId, rolle, aktion);
            return;
        }
        Instant ausgestellt = identitaet.getPrincipal() instanceof JsonWebToken jwt
                ? Instant.ofEpochSecond(jwt.getIssuedAtTime()) : Instant.EPOCH;
        if (tokenWiderruf.widerrufen(benutzerId.get(), ausgestellt)) {
            throw new FachFehler(Fehlercode.NICHT_ANGEMELDET);
        }
        if (aktion.isPresent()) {
            aktion(new Benutzer(benutzerId.get(), rolle.get()), aktion.get(),
                    methode.isAnnotationPresent(NichtDokumentbezogen.class) ? null
                            : anfrage.getUriInfo().getPathParameters().getFirst(DOKUMENT_ID));
        } else if (!methode.isAnnotationPresent(AlleArchivRollen.class)) {
            berechtigung.ohneZugang(benutzerId, rolle, aktion);
        }
    }

    private static boolean webseitenScopePasst(WebseitenZugang zugang,
                                                ch.urinet.archiv.sicherheit.domain.ApiTokenScope scope) {
        return zugang.value() == WebseitenZugang.Art.LESEN
                ? scope == ch.urinet.archiv.sicherheit.domain.ApiTokenScope.WEBSEITE_LESEN
                : scope == ch.urinet.archiv.sicherheit.domain.ApiTokenScope.WEBSEITE_ERFASSUNG;
    }

    /** API-Token (Req 2.5): nur der Endpunkt, der seinen Scope erlaubt; keine Archiv-Rolle, keine Rechtematrix. */
    private void scopePruefen(Method methode, Object scope, Optional<String> benutzerId, Optional<Aktion> aktion) {
        ErlaubtScope erlaubt = methode.getAnnotation(ErlaubtScope.class);
        if (benutzerId.isEmpty() || erlaubt == null || erlaubt.value() != scope) {
            berechtigung.ohneZugang(benutzerId, Optional.empty(), aktion);
        }
    }

    private void aktion(Benutzer benutzer, Aktion aktion, String dokumentId) {
        if (!aktion.dokumentbezogen() || dokumentId == null) {
            berechtigung.aktion(benutzer, aktion);
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(dokumentId);
        } catch (IllegalArgumentException e) {
            berechtigung.unbekanntesDokument(benutzer, aktion);
            return;
        }
        berechtigung.dokumentAktion(benutzer, aktion, id);
    }
}
