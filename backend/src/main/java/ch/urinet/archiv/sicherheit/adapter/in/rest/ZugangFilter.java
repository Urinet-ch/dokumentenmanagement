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
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.Wissensfreischaltung;
import ch.urinet.archiv.wissen.application.Wissenspfade;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>
 * Wissensbasis (Spec 04, Req 22.6): Gehört die Pfadvorlage zur Wissensbasis ({@link Wissenspfade}) und ist Wissen für
 * den Mandanten nicht freigeschaltet ({@link Wissensfreischaltung}), antwortet der Filter nach der Anmeldung (401, auch
 * widerrufene Sitzung) und vor Rechtematrix, Dokumentprüfung (404) und Laden von Daten mit 403
 * {@code WISSEN_NICHT_FREIGESCHALTET}, für Rollen und API-Tokens gleich.
 */
public class ZugangFilter {

    static final String DOKUMENT_ID = "id";

    private final SecurityIdentity identitaet;
    private final MandantAufloesung aufloesung;
    private final Berechtigung berechtigung;
    private final TokenWiderruf tokenWiderruf;
    private final Wissensfreischaltung wissen;
    private static final Map<Method, Boolean> WISSENSPFAD = new ConcurrentHashMap<>();

    public ZugangFilter(SecurityIdentity identitaet, MandantAufloesung aufloesung, Berechtigung berechtigung,
                        TokenWiderruf tokenWiderruf, Wissensfreischaltung wissen) {
        this.identitaet = identitaet;
        this.aufloesung = aufloesung;
        this.berechtigung = berechtigung;
        this.tokenWiderruf = tokenWiderruf;
        this.wissen = wissen;
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
        MandantCode mandant = null;
        try {
            mandant = aufloesung.pruefen();
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
            if (benutzerId.isPresent()) {
                wissenPruefen(ressource, mandant);
            }
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
        wissenPruefen(ressource, mandant);
        if (aktion.isPresent()) {
            aktion(new Benutzer(benutzerId.get(), rolle.get()), aktion.get(),
                    methode.isAnnotationPresent(NichtDokumentbezogen.class) ? null
                            : anfrage.getUriInfo().getPathParameters().getFirst(DOKUMENT_ID));
        } else if (!methode.isAnnotationPresent(AlleArchivRollen.class)) {
            berechtigung.ohneZugang(benutzerId, rolle, aktion);
        }
    }

    private void wissenPruefen(ResourceInfo ressource, MandantCode mandant) {
        if (mandant != null && wissenspfad(ressource.getResourceClass(), ressource.getResourceMethod())
                && !wissen.freigeschaltet(mandant)) {
            throw new FachFehler(Fehlercode.WISSEN_NICHT_FREIGESCHALTET);
        }
    }

    /** Gehört die Vorlage aus {@code @Path} von Klasse und Methode zur Wissensbasis ({@link Wissenspfade})? */
    static boolean wissenspfad(Class<?> klasse, Method methode) {
        return WISSENSPFAD.computeIfAbsent(methode, m -> Wissenspfade.gesperrt(
                vorlage(klasse != null ? klasse : m.getDeclaringClass(), m)));
    }

    static String vorlage(Class<?> klasse, Method methode) {
        Path aussen = klasse.getAnnotation(Path.class);
        Path innen = methode.getAnnotation(Path.class);
        String pfad = (aussen == null ? "" : "/" + aussen.value()) + (innen == null ? "" : "/" + innen.value());
        return pfad.replaceAll("/+", "/");
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
