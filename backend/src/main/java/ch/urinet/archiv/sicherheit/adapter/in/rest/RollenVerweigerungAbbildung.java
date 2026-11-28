package ch.urinet.archiv.sicherheit.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.adapter.in.rest.ProblemAbbildung;
import ch.urinet.archiv.gemeinsam.adapter.in.rest.ProblemAntwort;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantVerweigert;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.wissen.application.Wissensfreischaltung;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.TokenIdentitaet;
import ch.urinet.archiv.sicherheit.application.TokenWiderruf;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableContext;
import io.quarkus.arc.ManagedContext;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 403 aus {@code @RolesAllowed} (die Rolle darf den Endpunkt nie, z. B. {@code treuhaender} bei A1): Die Prüfung läuft
 * vor dem {@link ZugangFilter}. Darum prüft die Abbildung zuerst den Mandanten (403 {@code MANDANT_FEHLT}, Req 1.3)
 * und meldet die Verweigerung sonst über {@link Berechtigung} (Zugriffsprotokoll {@code VERWEIGERT}, Req 10.7).
 * Die Prüfung von {@code @RolesAllowed} scheitert auf dem Event-Loop; Mandantenprüfung und Protokoll brauchen JDBC und
 * JTA und laufen darum auf einem Worker-Thread, im Request-Kontext der Anfrage.
 * <p>
 * Wissensbasis (Spec 04, Req 22.6): Gehört der Endpunkt zur Wissensbasis und ist Wissen für den Mandanten nicht
 * freigeschaltet, antwortet sie wie der {@link ZugangFilter} mit 403 {@code WISSEN_NICHT_FREIGESCHALTET}, damit diese
 * Antwort unabhängig von der Rolle auch {@code @RolesAllowed} vorgeht. Davor gilt wie im {@link ZugangFilter} die
 * widerrufene Sitzung einer Rolle (401 {@code NICHT_ANGEMELDET}, Req 22.6 «nach 401»), auch für die übrigen Verweigerungen.
 */
public class RollenVerweigerungAbbildung {

    private final SecurityIdentity identitaet;
    private final MandantAufloesung aufloesung;
    private final Berechtigung berechtigung;
    private final Wissensfreischaltung wissen;
    private final TokenWiderruf widerruf;

    public RollenVerweigerungAbbildung(SecurityIdentity identitaet, MandantAufloesung aufloesung,
                                       Berechtigung berechtigung, Wissensfreischaltung wissen,
                                       TokenWiderruf widerruf) {
        this.identitaet = identitaet;
        this.aufloesung = aufloesung;
        this.berechtigung = berechtigung;
        this.wissen = wissen;
        this.widerruf = widerruf;
    }

    @ServerExceptionMapper
    public Uni<Response> verweigert(ForbiddenException fehler, ResourceInfo ressource) {
        Method methode = ressource == null ? null : ressource.getResourceMethod();
        Class<?> klasse = ressource == null ? null : ressource.getResourceClass();
        ManagedContext anfrageKontext = Arc.container().requestContext();
        InjectableContext.ContextState zustand = anfrageKontext.getStateIfActive();
        return Uni.createFrom().item(() -> imKontext(anfrageKontext, zustand, () -> antwort(klasse, methode)))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private static Response imKontext(ManagedContext anfrageKontext, InjectableContext.ContextState zustand,
                                      Supplier<Response> arbeit) {
        if (anfrageKontext.isActive() || zustand == null) {
            return arbeit.get();
        }
        anfrageKontext.activate(zustand);
        try {
            return arbeit.get();
        } finally {
            anfrageKontext.deactivate();
        }
    }

    private Response antwort(Class<?> klasse, Method methode) {
        Optional<String> benutzerId = identitaet.isAnonymous() ? Optional.empty()
                : Optional.ofNullable(identitaet.getPrincipal().getName()).filter(id -> !id.isBlank());
        try {
            MandantCode mandant = null;
            try {
                mandant = aufloesung.pruefen();
            } catch (MandantVerweigert e) {
                berechtigung.ohneMandant(benutzerId);
            }
            Optional<Rolle> rolle = Rolle.ausRealmRollen(identitaet.getRoles());
            if (rolle.isPresent() && benutzerId.isPresent()
                    && identitaet.getAttribute(TokenIdentitaet.SCOPE) == null) {
                // widerrufene Sitzung: 401 wie im ZugangFilter, vor Wissenssperre und Verweigerung (Spec 04, Req 22.6)
                ZugangFilter.sitzungPruefen(identitaet, widerruf, benutzerId.get());
            }
            if (mandant != null && methode != null && ZugangFilter.wissenspfad(klasse, methode)
                    && !wissen.freigeschaltet(mandant)) {
                return ProblemAbbildung.antwort(ProblemAntwort.fuer(Fehlercode.WISSEN_NICHT_FREIGESCHALTET));
            }
            Optional<Aktion> aktion = Optional.ofNullable(methode)
                    .map(m -> m.getAnnotation(ErfordertAktion.class)).map(ErfordertAktion::value);
            berechtigung.ohneZugang(benutzerId, rolle, aktion);
            throw new IllegalStateException("Berechtigung hat nicht verweigert");
        } catch (Verweigerung v) {
            return ProblemAbbildung.antwort(ProblemAntwort.fuer(v.code()));
        } catch (FachFehler f) {
            return new ProblemAbbildung().fachFehler(f);
        }
    }
}
