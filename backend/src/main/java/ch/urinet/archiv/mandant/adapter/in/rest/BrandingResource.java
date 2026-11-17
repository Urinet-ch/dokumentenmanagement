package ch.urinet.archiv.mandant.adapter.in.rest;

import ch.urinet.archiv.mandant.application.BrandingAbfragen;
import ch.urinet.archiv.mandant.domain.Branding;
import ch.urinet.archiv.sicherheit.application.Oeffentlich;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/branding")
@Tag(name = "Branding")
@Produces(MediaType.APPLICATION_JSON)
public class BrandingResource {

    public record BrandingDto(String name, String logoUrl, String primaerfarbe, String sprache) {
        static BrandingDto aus(Branding branding) {
            return new BrandingDto(branding.name(), branding.logoUrl(), branding.primaerfarbe(), branding.sprache());
        }
    }

    private final BrandingAbfragen branding;

    public BrandingResource(BrandingAbfragen branding) {
        this.branding = branding;
    }

    @GET
    @PermitAll
    @Oeffentlich
    @Operation(summary = "Lädt das Branding für eine registrierte Domain oder das neutrale Standard-Branding.")
    @APIResponse(responseCode = "200", description = "Branding",
            content = @org.eclipse.microprofile.openapi.annotations.media.Content(
                    schema = @Schema(implementation = BrandingDto.class)))
    @APIResponse(responseCode = "400", description = "Ungültiger Hostname")
    public Response laden(@Parameter(description = "Registrierte Domain, ohne Schema oder Port")
                          @QueryParam("domain") String domain) {
        return Response.ok(BrandingDto.aus(branding.fuerDomain(domain)))
                .header("Cache-Control", "public, max-age=60")
                .build();
    }
}
