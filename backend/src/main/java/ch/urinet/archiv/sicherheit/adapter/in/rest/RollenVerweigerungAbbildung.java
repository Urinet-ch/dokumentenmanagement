package ch.urinet.archiv.sicherheit.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.adapter.in.rest.ProblemAbbildung;
import ch.urinet.archiv.gemeinsam.adapter.in.rest.ProblemAntwort;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantVerweigert;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 403 aus {@code @RolesAllowed} (die Rolle darf den Endpunkt nie, z. B. {@code treuhaender} bei A1): Die Prüfung läuft
 * vor dem {@link ZugangFilter}. Darum prüft die Abbildung zuerst den Mandanten (403 {@code MANDANT_FEHLT}, Req 1.3)
 * und meldet die Verweigerung sonst über {@link Berechtigung} (Zugriffsprotokoll {@code VERWEIGERT}, Req 10.7).
 */
public class RollenVerweigerungAbbildung {

    private final SecurityIdentity identitaet;
    private final MandantAufloesung aufloesung;
    private final Berechtigung berechtigung;

    public RollenVerweigerungAbbildung(SecurityIdentity identitaet, MandantAufloesung aufloesung,
                                       Berechtigung berechtigung) {
        this.identitaet = identitaet;
        this.aufloesung = aufloesung;
        this.berechtigung = berechtigung;
    }

    @ServerExceptionMapper
    public Response verweigert(ForbiddenException fehler, ResourceInfo ressource) {
        Optional<String> benutzerId = identitaet.isAnonymous() ? Optional.empty()
                : Optional.ofNullable(identitaet.getPrincipal().getName()).filter(id -> !id.isBlank());
        try {
            try {
                aufloesung.pruefen();
            } catch (MandantVerweigert e) {
                berechtigung.ohneMandant(benutzerId);
            }
            Method methode = ressource == null ? null : ressource.getResourceMethod();
            Optional<Aktion> aktion = Optional.ofNullable(methode)
                    .map(m -> m.getAnnotation(ErfordertAktion.class)).map(ErfordertAktion::value);
            Optional<Rolle> rolle = Rolle.ausRealmRollen(identitaet.getRoles());
            berechtigung.ohneZugang(benutzerId, rolle, aktion);
            throw new IllegalStateException("Berechtigung hat nicht verweigert");
        } catch (Verweigerung v) {
            return ProblemAbbildung.antwort(ProblemAntwort.fuer(v.code()));
        }
    }
}
