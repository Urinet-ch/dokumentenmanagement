package ch.urinet.archiv.integration.webseite.gegenprobe;

import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Gegenprobe für {@code PublicVertragTest} und {@code OpenApiAusschlussTest} (Spec 03, Aufgabe 11.6): ein Endpunkt
 * unter {@code /public/v1}, den nur der Index dieser Tests enthält. Die Eigenschaft wird nie gesetzt, darum
 * registriert Quarkus die Resource nicht; das Paket liegt unter {@code ch.urinet.archiv.integration.webseite} und damit
 * im Ausschluss der App-OpenAPI.
 */
@IfBuildProperty(name = GegenprobeResource.SCHALTER, stringValue = "an")
@Path("/public/v1/gegenprobe")
public class GegenprobeResource {

    public static final String SCHALTER = "archiv.test.vertrag-gegenprobe";

    public record Eintrag(String id, String titel) {
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @APIResponse(responseCode = "200", description = "Eintrag",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Eintrag.class)))
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    public Eintrag lesen(@PathParam("id") String id, @QueryParam("stufe") String stufe) {
        return new Eintrag(id, stufe);
    }
}
