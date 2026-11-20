package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.konfiguration.application.Funktionen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/mandant/funktionen")
@Tag(name = "Konfiguration")
@Produces(MediaType.APPLICATION_JSON)
public class FunktionenResource {

    private final Funktionen funktionen;
    private final MandantAufloesung aufloesung;

    public FunktionenResource(Funktionen funktionen, MandantAufloesung aufloesung) {
        this.funktionen = funktionen;
        this.aufloesung = aufloesung;
    }

    @Schema(name = "Mandantenfunktionen")
    public record FunktionenDto(@Schema(required = true,
            description = "Mindestens eine aktive Dokumentart hat die Gruppe «finanz» (Spec 05, Req 8.8)")
                                  boolean finanzVerfuegbar) {
    }

    @GET
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Verfügbare Funktionen des Mandanten")
    @APIResponse(responseCode = "200", description = "Funktionen des aktuellen Mandanten")
    public FunktionenDto lesen() {
        aufloesung.pruefen();
        return new FunktionenDto(funktionen.finanzVerfuegbar());
    }
}
