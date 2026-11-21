package ch.urinet.archiv.benutzer.adapter.in.rest;

import ch.urinet.archiv.benutzer.application.BenutzerLesen;
import ch.urinet.archiv.benutzer.application.BenutzerEinladen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/benutzer")
@Tag(name = "Benutzerverwaltung")
@Produces(MediaType.APPLICATION_JSON)
public class BenutzerResource {
    private final BenutzerLesen lesen;
    private final BenutzerEinladen einladen;
    private final MandantAufloesung mandant;
    private final SecurityIdentity identitaet;

    public BenutzerResource(BenutzerLesen lesen, BenutzerEinladen einladen, MandantAufloesung mandant,
                            SecurityIdentity identitaet) {
        this.lesen = lesen;
        this.einladen = einladen;
        this.mandant = mandant;
        this.identitaet = identitaet;
    }

    @GET
    @ErfordertAktion(Aktion.A21)
    @RolesAllowed("inhaber")
    @Operation(summary = "Benutzer des Mandanten auflisten")
    public List<BenutzerLesen.Ansicht> suchen(@QueryParam("q") String suche,
                                              @QueryParam("seite") @DefaultValue("0") int seite,
                                              @QueryParam("groesse") @DefaultValue("50") int groesse) {
        return lesen.suchen(suche, seite, groesse);
    }

    @GET
    @Path("/{benutzerId}")
    @ErfordertAktion(Aktion.A21)
    @RolesAllowed("inhaber")
    @Operation(summary = "Benutzer des Mandanten anzeigen")
    public Response lesen(@PathParam("benutzerId") String benutzerId) {
        BenutzerLesen.Ansicht ansicht = lesen.lesen(benutzerId);
        return Response.ok(ansicht).tag(ansicht.etag()).build();
    }

    @POST
    @Path("/{benutzerId}/einladung")
    @ErfordertAktion(Aktion.A22)
    @RolesAllowed("inhaber")
    @Operation(summary = "Einladung erneut senden")
    public Response einladung(@PathParam("benutzerId") String benutzerId, @HeaderParam("X-Real-Ip") String ip) {
        Rolle rolle = Rolle.ausRealmRollen(identitaet.getRoles())
                .orElseThrow(() -> new IllegalStateException("Archiv-Rolle fehlt"));
        einladen.erneut(mandant.pruefen(), new Benutzer(identitaet.getPrincipal().getName(), rolle),
                benutzerId, IpAdresse.aus(ip));
        return Response.noContent().build();
    }
}
