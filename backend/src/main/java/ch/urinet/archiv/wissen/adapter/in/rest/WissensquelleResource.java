package ch.urinet.archiv.wissen.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.wissen.application.WissensquelleMarkieren;
import ch.urinet.archiv.wissen.application.WissensquelleMarkierungPort;
import ch.urinet.archiv.wissen.application.WissenIngestStarten;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

@Path("/")
@Tag(name = "Wissensbasis")
@Produces(MediaType.APPLICATION_JSON)
public class WissensquelleResource {
    private final WissensquelleMarkieren markieren;
    private final MandantAufloesung mandant;
    private final SecurityIdentity identitaet;

    public WissensquelleResource(WissensquelleMarkieren markieren, MandantAufloesung mandant,
                                 SecurityIdentity identitaet) {
        this.markieren = markieren;
        this.mandant = mandant;
        this.identitaet = identitaet;
    }

    @POST
    @Path("/dokumente/{id}/wissensquelle")
    @ErfordertAktion(Aktion.A16)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.WISSENSQUELLE_MARKIERT)
    @Operation(summary = "Dokument als Wissensquelle verwenden")
    @APIResponse(responseCode = "202", description = "Markierung gespeichert und Ingest angestossen",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = WissenIngestResource.AuftragDto.class)))
    @APIResponse(responseCode = "403", description = "Recht A16 fehlt")
    @APIResponse(responseCode = "404", description = "Dokument unbekannt oder nicht sichtbar")
    @APIResponse(responseCode = "409", description = "Dokument bereits markiert oder Ingest läuft")
    @APIResponse(responseCode = "422", description = "Fachliche Voraussetzung fehlt")
    public Response markieren(@PathParam("id") UUID id) {
        mandant.pruefen();
        WissenIngestStarten.Ergebnis ergebnis = markieren.markieren(id, benutzer().id());
        return Response.accepted(new WissenIngestResource.AuftragDto(ergebnis.entwurfId(), ergebnis.status(),
                ergebnis.geschaetzteTokens(), ergebnis.hinweis())).build();
    }

    @DELETE
    @Path("/dokumente/{id}/wissensquelle")
    @ErfordertAktion(Aktion.A16)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.WISSENSQUELLE_ENTFERNT)
    @Operation(summary = "Markierung als Wissensquelle entfernen")
    @APIResponse(responseCode = "204", description = "Markierung beendet")
    @APIResponse(responseCode = "403", description = "Recht A16 fehlt")
    @APIResponse(responseCode = "404", description = "Dokument unbekannt oder nicht sichtbar")
    @APIResponse(responseCode = "409", description = "Dokument ist nicht markiert")
    public Response entfernen(@PathParam("id") UUID id) {
        mandant.pruefen();
        markieren.entfernen(id, benutzer().id());
        return Response.noContent().build();
    }

    @GET
    @Path("/wissen/quellen")
    @ErfordertAktion(Aktion.A15)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.WISSEN_ANZEIGEN)
    @Operation(summary = "Sichtbare Wissensquellen auflisten")
    public List<WissensquelleMarkierungPort.Quelle> quellen() {
        mandant.pruefen();
        return markieren.quellen(Sichtregel.fuer(benutzer()));
    }

    private Benutzer benutzer() {
        Rolle rolle = Rolle.ausRealmRollen(identitaet.getRoles())
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_ERLAUBT));
        return new Benutzer(identitaet.getPrincipal().getName(), rolle);
    }
}
