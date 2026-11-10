package ch.urinet.archiv.probe;

import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * Probe nur für Tests: nimmt JSON mit einem Zahlenfeld an. Belegt Problem Details bei falschem Feldtyp (400), falschem
 * Content-Type (415) und zu grosser Anfrage (413) – Vorab-Korrekturen 2, 4 und 8.
 */
@Path("/probe/anfrage")
public class ProbeAnfrageResource {

    public record Eingabe(int anzahl) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    public Map<String, Integer> senden(Eingabe eingabe) {
        return Map.of("anzahl", eingabe.anzahl());
    }
}
