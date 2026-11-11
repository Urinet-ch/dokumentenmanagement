package ch.urinet.archiv.dokument.adapter.in.rest;

import ch.urinet.archiv.dokument.application.DokumentLesen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.io.InputStream;
import java.util.UUID;

/**
 * Vorschaubild einer Seite (A2–A4, Req 8.1, design.md «REST-API»); Sichtbarkeit wie beim Lesen der Metadaten (404 für
 * unsichtbare und fremde Dokumente). Eigene Klasse neben der {@link DokumentResource}, weil das Bild kein JSON ist.
 */
@Path("/dokumente")
@Tag(name = "Dokumente")
public class DokumentVorschauResource {

    static final String PNG = "image/png";

    private final DokumentLesen lesen;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public DokumentVorschauResource(DokumentLesen lesen, MandantAufloesung aufloesung, SecurityIdentity identitaet) {
        this.lesen = lesen;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @GET
    @Path("/{id}/vorschau")
    @Produces(PNG)
    @ErfordertAktion(Aktion.A2)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.ANZEIGEN)
    @Operation(summary = "Vorschaubild einer Seite lesen",
            description = "PNG der Seite (ab 1, Standard 1). Unsichtbares oder fremdes Dokument, Seite über der "
                    + "Seitenzahl oder noch ohne Vorschau → 404 NICHT_GEFUNDEN; Seite unter 1 → 400.")
    @APIResponse(responseCode = "200", description = "Vorschaubild",
            content = @Content(mediaType = PNG, schema = @Schema(type = SchemaType.STRING, format = "binary")))
    public Response vorschau(@PathParam("id") UUID id, @QueryParam("seite") @DefaultValue("1") int seite) {
        aufloesung.pruefen();
        InputStream bild = lesen.vorschau(DokumentResource.benutzer(identitaet), id, seite);
        StreamingOutput ausgabe = ziel -> {
            try (bild) {
                bild.transferTo(ziel);
            }
        };
        return Response.ok(ausgabe, PNG).header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
    }
}
