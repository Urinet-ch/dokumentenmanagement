package ch.urinet.archiv.probe;

import ch.urinet.archiv.dokumentart.adapter.out.persistence.DokumentartEintrag;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * Probe-Endpunkte nur für Tests (Aufgabe 1.4): liegen unter {@code src/test} und damit nie im Produktionsartefakt
 * ({@code ProbeNichtImArtefaktTest}). Sie belegen Mandantentrennung und Rechteprüfung, bevor es echte Endpunkte gibt.
 */
@Path("/probe")
public class ProbeResource {

    /** Liest eine Dokumentart im Schema des angemeldeten Mandanten; fremde Codes antworten 404 (Req 1.4). */
    @GET
    @Path("/dokumentarten/{code}")
    @Produces(MediaType.APPLICATION_JSON)
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Transactional
    public Map<String, String> dokumentart(@PathParam("code") String code) {
        DokumentartEintrag art = DokumentartEintrag.findById(code);
        if (art == null) {
            throw new NotFoundException();
        }
        return Map.of("code", art.code, "name", art.name);
    }

    /** Steht für Aktion A1 «Erfassen» (Req 2.6); ohne Wirkung. */
    @POST
    @Path("/erfassen")
    @ErfordertAktion(Aktion.A1)
    @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle"})
    public void erfassen() {
        // nur die Rechteprüfung zählt
    }
}
