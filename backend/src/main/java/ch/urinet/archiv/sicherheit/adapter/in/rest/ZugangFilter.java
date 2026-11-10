package ch.urinet.archiv.sicherheit.adapter.in.rest;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantVerweigert;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.Oeffentlich;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Prüft jede Anfrage an einen Endpunkt, bevor Daten gelesen werden (Req 1.3, 2.2, 2.6): gültiger, aktiver Mandant aus
 * dem Token, eine Archiv-Rolle (die stärkste gilt) und – bei {@link ErfordertAktion} – das Recht der Aktion nach der
 * Rechtematrix. Bei dokumentbezogenen Aktionen (A2–A10) mit Pfad-Parameter {@code id} gilt 404 vor 403
 * ({@link Berechtigung#dokumentAktion}); {@code @RolesAllowed} lässt dort alle Archiv-Rollen zu. Ein Endpunkt ohne
 * Erklärung ({@link ErfordertAktion}, {@link AlleArchivRollen}, {@link Oeffentlich}) antwortet 403 (fail closed);
 * {@code EndpunktWaechterTest} verlangt die Erklärung schon im Build.
 */
public class ZugangFilter {

    static final String DOKUMENT_ID = "id";

    private final SecurityIdentity identitaet;
    private final MandantAufloesung aufloesung;
    private final Berechtigung berechtigung;

    public ZugangFilter(SecurityIdentity identitaet, MandantAufloesung aufloesung, Berechtigung berechtigung) {
        this.identitaet = identitaet;
        this.aufloesung = aufloesung;
        this.berechtigung = berechtigung;
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
            berechtigung.ohneZugang(benutzerId);
        }
        Optional<Rolle> rolle = Rolle.ausRealmRollen(identitaet.getRoles());
        if (rolle.isEmpty() || benutzerId.isEmpty()) {
            berechtigung.ohneZugang(benutzerId);
            return;
        }
        ErfordertAktion erklaerung = methode.getAnnotation(ErfordertAktion.class);
        if (erklaerung != null) {
            aktion(new Benutzer(benutzerId.get(), rolle.get()), erklaerung.value(),
                    anfrage.getUriInfo().getPathParameters().getFirst(DOKUMENT_ID));
        } else if (!methode.isAnnotationPresent(AlleArchivRollen.class)) {
            berechtigung.ohneZugang(benutzerId);
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
