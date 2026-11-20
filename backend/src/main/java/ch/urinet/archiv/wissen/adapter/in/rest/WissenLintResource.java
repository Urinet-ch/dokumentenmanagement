package ch.urinet.archiv.wissen.adapter.in.rest;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.wissen.application.WissenLintStarten;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import java.util.UUID;

@Path("/wissensbasis/lint")
@Produces(MediaType.APPLICATION_JSON)
public class WissenLintResource {
    private final WissenLintStarten starten;
    private final MandantAufloesung mandant;
    private final SecurityIdentity identitaet;

    public WissenLintResource(WissenLintStarten starten, MandantAufloesung mandant, SecurityIdentity identitaet) {
        this.starten = starten;
        this.mandant = mandant;
        this.identitaet = identitaet;
    }

    @Schema(name = "WissenLintAuftrag")
    public record LintAuftrag(UUID lintId) {
    }

    @POST
    @ErfordertAktion(Aktion.A17)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.WISSEN_LINT)
    @Operation(summary = "Lint der Wissensbasis starten")
    @APIResponse(responseCode = "202", description = "Lint eingereiht",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = LintAuftrag.class)))
    @APIResponse(responseCode = "403", description = "Rolle darf keinen Lint starten")
    @APIResponse(responseCode = "409", description = "Ein Lint läuft bereits")
    @APIResponse(responseCode = "429", description = "Monatsbudget der Wissensbasis erschöpft")
    public Response starten() {
        mandant.pruefen();
        return Response.accepted(new LintAuftrag(starten.manuell(identitaet.getPrincipal().getName()))).build();
    }
}
