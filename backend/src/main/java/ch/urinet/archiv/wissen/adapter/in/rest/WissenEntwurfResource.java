package ch.urinet.archiv.wissen.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenEntwurfLesen;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

@Path("/wissensentwuerfe")
@Tag(name = "Wissensbasis")
@Produces(MediaType.APPLICATION_JSON)
public class WissenEntwurfResource {
    private final WissenEntwurfLesen lesen;
    private final MandantAufloesung mandant;
    private final SecurityIdentity identitaet;

    public WissenEntwurfResource(WissenEntwurfLesen lesen, MandantAufloesung mandant, SecurityIdentity identitaet) {
        this.lesen = lesen;
        this.mandant = mandant;
        this.identitaet = identitaet;
    }

    @GET
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Wissensentwürfe auflisten",
            description = "A17 sieht alle Entwürfe; A18 nur den Status eigener Analysevorschläge.")
    @APIResponse(responseCode = "200", description = "Entwürfe")
    @APIResponse(responseCode = "403", description = "Rolle darf keine Entwürfe lesen")
    public Response liste(@QueryParam("status") String status) {
        mandant.pruefen();
        return privat(lesen.liste(benutzer(), status));
    }

    @GET
    @Path("/{id}")
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.WISSEN_ANZEIGEN)
    @Operation(summary = "Wissensentwurf abrufen",
            description = "A17 sieht Fassungen und Diffs; A18 nur Status, Entscheide und Gründe eigener Analysen.")
    @APIResponse(responseCode = "200", description = "Entwurf")
    @APIResponse(responseCode = "403", description = "Rolle darf den Entwurf nicht lesen")
    @APIResponse(responseCode = "404", description = "Entwurf nicht gefunden")
    public Response lesen(@PathParam("id") UUID id) {
        mandant.pruefen();
        return privat(lesen.lesen(benutzer(), id));
    }

    private Benutzer benutzer() {
        Rolle rolle = Rolle.ausRealmRollen(identitaet.getRoles())
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_ERLAUBT));
        return new Benutzer(identitaet.getPrincipal().getName(), rolle);
    }

    private static Response privat(Object daten) {
        CacheControl cache = new CacheControl();
        cache.setPrivate(true);
        cache.setNoStore(true);
        return Response.ok(daten).cacheControl(cache).build();
    }
}
