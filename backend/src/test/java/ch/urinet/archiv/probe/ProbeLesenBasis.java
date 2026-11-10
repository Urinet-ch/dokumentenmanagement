package ch.urinet.archiv.probe;

import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestPath;

import java.util.Map;
import java.util.UUID;

/**
 * Oberklasse der {@link ProbeGeerbtResource} (Vorab-Korrektur 7): Die Methode wird geerbt, {@code @Protokolliert}
 * steht nur an der Bean-Klasse, und die Dokument-ID kommt über {@code @RestPath} statt {@code @PathParam}.
 */
public abstract class ProbeLesenBasis {

    @GET
    @Path("/dokumente/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A2)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    public Map<String, String> lesen(@RestPath UUID id) {
        return Map.of("id", id.toString());
    }
}
