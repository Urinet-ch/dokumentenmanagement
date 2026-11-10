package ch.urinet.archiv.probe;

import ch.urinet.archiv.dokumentart.adapter.out.persistence.DokumentartEintrag;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
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
 * (ArchUnit-Regel {@code ArchitekturTest.probeNurInTests}). Sie belegen Mandantentrennung und Rechteprüfung, bevor es
 * echte Endpunkte gibt. Die Probe-Dokumente liefert {@link ProbeSichtbarkeit}.
 */
@Path("/probe")
public class ProbeResource {

    @Inject
    SecurityIdentity identitaet;

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

    /** Liest ein Probe-Dokument (A2; INTERN und PERSONAL schränkt die Sichtbarkeit ein, Req 2.3). */
    @GET
    @Path("/dokumente/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A2)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    public Map<String, String> dokument(@PathParam("id") String id) {
        return Map.of("id", id);
    }

    /** Steht für Aktion A7 «Bestätigen» auf einem Dokument: 404 vor 403 (Req 2.6); ohne Wirkung. */
    @POST
    @Path("/dokumente/{id}/bestaetigen")
    @ErfordertAktion(Aktion.A7)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    public void bestaetigen(@PathParam("id") String id) {
        // nur die Rechteprüfung zählt
    }

    /** Benutzer-ID der Anmeldung (Claim {@code sub}, design.md «Sicherheit»). */
    @GET
    @Path("/ich")
    @Produces(MediaType.APPLICATION_JSON)
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    public Map<String, String> ich() {
        return Map.of("id", identitaet.getPrincipal().getName());
    }
}
