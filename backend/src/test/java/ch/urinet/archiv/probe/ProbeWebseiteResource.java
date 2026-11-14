package ch.urinet.archiv.probe;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * Probe der Webseiten-Schnittstelle nur für Tests (Spec 03, Aufgabe 11.6): erreichbar unter {@code /public/v1/probe}
 * über den Reroute von {@code WebseitenRoute}; liegt unter {@code src/test} im Paket {@code probe} und damit weder in der
 * App-OpenAPI noch im Vertrag {@code public-v1.yaml}. Belegt Problem Details, {@code Cache-Control} und die Header der
 * Abkündigung, bevor es echte Endpunkte gibt.
 * <p>
 * Die Erklärung {@link AlleArchivRollen} mit allen Archiv-Rollen ist die, die {@code EndpunktWaechterTest} heute annimmt;
 * Aufgabe 11.7 stellt sie auf {@code @WebseitenZugang} um, sobald es Webseiten-Schlüssel gibt.
 */
@Path("/public/v1/probe")
public class ProbeWebseiteResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    public Map<String, String> lesen() {
        return Map.of("probe", "ok");
    }

    /** Ungültiger Parameter → 400 {@code ANFRAGE_UNGUELTIG}, ohne den Wert zu nennen. */
    @GET
    @Path("/zahl")
    @Produces(MediaType.APPLICATION_JSON)
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    public Map<String, Integer> zahl(@QueryParam("wert") String wert) {
        try {
            return Map.of("wert", Integer.parseInt(wert));
        } catch (NumberFormatException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    /** Unerwarteter Fehler mit dem Text in der Meldung: er darf nicht in der Antwort stehen. */
    @GET
    @Path("/fehler/{text}")
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    public void fehler(@PathParam("text") String text) {
        throw new IllegalStateException("Probe " + text);
    }

    /** Nimmt eine Anfrage mit Inhalt an; belegt 413 vor der Resource. */
    @POST
    @Path("/inhalt")
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    public void inhalt(String inhalt) {
        // nur die Grenze der Anfrage zählt
    }
}
