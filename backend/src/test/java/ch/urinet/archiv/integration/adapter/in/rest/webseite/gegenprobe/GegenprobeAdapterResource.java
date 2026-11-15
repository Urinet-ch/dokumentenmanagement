package ch.urinet.archiv.integration.adapter.in.rest.webseite.gegenprobe;

import ch.urinet.archiv.integration.webseite.gegenprobe.GegenprobeResource;
import ch.urinet.archiv.sicherheit.application.WebseitenZugang;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * Gegenprobe für {@code OpenApiAusschlussTest}: Resource in einem Unterpaket von
 * {@code ch.urinet.archiv.integration.adapter.in.rest.webseite}, nur im Index des Tests (die Eigenschaft wird nie
 * gesetzt, Quarkus registriert sie nicht).
 */
@IfBuildProperty(name = GegenprobeResource.SCHALTER, stringValue = "an")
@Path("/public/v1/gegenprobe-adapter")
public class GegenprobeAdapterResource {

    @GET
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    public String lesen() {
        return "probe";
    }
}
