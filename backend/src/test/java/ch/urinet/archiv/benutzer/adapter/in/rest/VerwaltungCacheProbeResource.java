package ch.urinet.archiv.benutzer.adapter.in.rest;

import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/verwaltung/cache-probe")
@Produces(MediaType.APPLICATION_JSON)
public class VerwaltungCacheProbeResource {

    @GET
    @Operation(hidden = true)
    @ErfordertAktion(Aktion.A21)
    @RolesAllowed("inhaber")
    public String lesen() {
        return "{}";
    }
}
