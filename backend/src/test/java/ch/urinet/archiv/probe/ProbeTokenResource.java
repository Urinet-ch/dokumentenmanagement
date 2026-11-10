package ch.urinet.archiv.probe;

import ch.urinet.archiv.dokumentart.adapter.out.persistence.DokumentartEintrag;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.ErlaubtScope;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

/**
 * Probe für die Scope-Endpunkte (Aufgabe 1.5, Req 2.5) mit den späteren Pfaden {@code POST /dokumente} (Scope
 * {@code import}, Aufgabe 3.x) und {@code POST /dokumente/generator} (Scope {@code generator}, Aufgabe 2.4); nur unter
 * {@code src/test}. Wird entfernt, sobald die echten Endpunkte kommen. Mit {@code art} liest sie die Dokumentart im
 * Schema des angemeldeten Mandanten (fremde Codes → 404).
 */
@Path("/dokumente")
public class ProbeTokenResource {

    @POST
    @ErfordertAktion(Aktion.A1)
    @ErlaubtScope(ApiTokenScope.IMPORT)
    @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle", ApiTokenScope.ROLLE_IMPORT})
    @Protokolliert(aktion = ProtokollAktion.ERFASSEN)
    @Transactional
    public Response importieren(@QueryParam("art") String art) {
        return angenommen(art);
    }

    @POST
    @Path("/generator")
    @ErfordertAktion(Aktion.A1)
    @ErlaubtScope(ApiTokenScope.GENERATOR)
    @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle", ApiTokenScope.ROLLE_GENERATOR})
    @Protokolliert(aktion = ProtokollAktion.ERFASSEN)
    @Transactional
    public Response generator(@QueryParam("art") String art) {
        return angenommen(art);
    }

    private static Response angenommen(String art) {
        if (art != null && DokumentartEintrag.findById(art) == null) {
            throw new NotFoundException();
        }
        return Response.accepted().build();
    }
}
