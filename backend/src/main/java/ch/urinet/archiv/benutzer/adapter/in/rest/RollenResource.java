package ch.urinet.archiv.benutzer.adapter.in.rest;

import ch.urinet.archiv.benutzer.application.MandantenRollen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Ergebnis;
import ch.urinet.archiv.sicherheit.domain.Fussnote;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Arrays;
import java.util.List;

@Path("/rollen")
@Tag(name = "Benutzerverwaltung")
@Produces(MediaType.APPLICATION_JSON)
public class RollenResource {

    private final MandantenRollen rollen;
    private final MandantAufloesung aufloesung;

    public RollenResource(MandantenRollen rollen, MandantAufloesung aufloesung) {
        this.rollen = rollen;
        this.aufloesung = aufloesung;
    }

    @Schema(name = "VerwaltungRolle")
    public record RolleDto(@Schema(required = true) String code, @Schema(required = true) String beschreibung,
                           @Schema(required = true) List<RechtDto> rechte) {
    }

    @Schema(name = "VerwaltungRollenrecht")
    public record RechtDto(@Schema(required = true) Aktion aktion, @Schema(required = true) String bezeichnung,
                           @Schema(required = true) Ergebnis ergebnis, @Schema(required = true) List<FussnoteDto> fussnoten) {
    }

    @Schema(name = "VerwaltungRechteFussnote")
    public record FussnoteDto(@Schema(required = true) String zeichen, @Schema(required = true) String text) {
    }

    @GET
    @ErfordertAktion(Aktion.A21)
    @RolesAllowed("inhaber")
    @Operation(summary = "Rollen und Rechte des Mandanten auflisten")
    @APIResponse(responseCode = "200", description = "Rollen des aktuellen Mandanten")
    @APIResponse(responseCode = "403", description = "NICHT_ERLAUBT")
    public List<RolleDto> lesen() {
        aufloesung.pruefen();
        return rollen.lesen().stream().sorted().map(RollenResource::darstellen).toList();
    }

    static RolleDto darstellen(Rolle rolle) {
        List<RechtDto> rechte = Arrays.stream(Aktion.values()).map(aktion ->
                new RechtDto(aktion, aktion.bezeichnung(), Rechtematrix.ergebnis(rolle, aktion),
                        Rechtematrix.fussnoten(rolle, aktion).stream().map(RollenResource::darstellen).toList()))
                .toList();
        return new RolleDto(rolle.code(), beschreibung(rolle), rechte);
    }

    private static FussnoteDto darstellen(Fussnote fussnote) {
        return new FussnoteDto(fussnote.zeichen(), fussnote.text());
    }

    private static String beschreibung(Rolle rolle) {
        return switch (rolle) {
            case INHABER -> "Inhaber des Mandanten";
            case MITARBEITER -> "Mitarbeiterin oder Mitarbeiter";
            case TREUHAENDER -> "Treuhänder, nur lesend";
            case GESCHAEFTSSTELLE -> "Geschäftsstelle";
        };
    }
}
