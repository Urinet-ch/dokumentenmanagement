package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.konfiguration.application.Funktionen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.Wissensfreischaltung;
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
    private final Wissensfreischaltung freischaltung;

    public FunktionenResource(Funktionen funktionen, MandantAufloesung aufloesung, Wissensfreischaltung freischaltung) {
        this.funktionen = funktionen;
        this.aufloesung = aufloesung;
        this.freischaltung = freischaltung;
    }

    @Schema(name = "Mandantenfunktionen")
    public record FunktionenDto(@Schema(required = true,
            description = "Mindestens eine aktive Dokumentart hat die Gruppe «finanz» (Spec 05, Req 8.8)")
                                  boolean finanzVerfuegbar,
                                  @Schema(required = true,
            description = "Wissen ist für den Mandanten freigeschaltet (Spec 04, Req 22.4)")
                                  boolean wissenVerfuegbar) {
    }

    @GET
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Verfügbare Funktionen des Mandanten")
    @APIResponse(responseCode = "200", description = "Funktionen des aktuellen Mandanten")
    public FunktionenDto lesen() {
        MandantCode mandant = aufloesung.pruefen();
        return new FunktionenDto(funktionen.finanzVerfuegbar(), freischaltung.freigeschaltet(mandant));
    }
}
