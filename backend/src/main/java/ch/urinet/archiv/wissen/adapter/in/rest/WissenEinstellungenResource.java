package ch.urinet.archiv.wissen.adapter.in.rest;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenEinstellungen;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/wissensbasis/einstellungen")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Wissensbasis")
public class WissenEinstellungenResource {
    @Schema(name = "WissenEinstellungen")
    public record WerteDto(@Schema(nullable = true, minimum = "1") Integer budgetTokens,
                           @Schema(nullable = true, minimum = "1") Integer lintBudgetTokens,
                           @Schema(nullable = true) String lintZeitplan,
                           @Schema(nullable = true, minimum = "1") Integer monatsbudgetTokens) {
    }

    private final WissenEinstellungen einstellungen;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public WissenEinstellungenResource(WissenEinstellungen einstellungen, MandantAufloesung aufloesung,
                                       SecurityIdentity identitaet) {
        this.einstellungen = einstellungen;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @GET
    @ErfordertAktion(Aktion.A19)
    @RolesAllowed("inhaber")
    @Operation(summary = "Wissenseinstellungen lesen",
            description = "Nur Inhaber (A19); null bedeutet den konfigurierten Startwert.")
    public WerteDto lesen() {
        aufloesung.pruefen();
        return dto(einstellungen.lesen(benutzer()));
    }

    @PUT
    @ErfordertAktion(Aktion.A19)
    @RolesAllowed("inhaber")
    @Operation(summary = "Wissenseinstellungen ersetzen",
            description = "Nur Inhaber (A19); null setzt den jeweiligen Startwert ein.")
    @APIResponse(responseCode = "200", description = "Einstellungen gespeichert",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = WerteDto.class)))
    @APIResponse(responseCode = "422", description = "Budget oder Zeitplan ungültig")
    public WerteDto speichern(WerteDto werte) {
        aufloesung.pruefen();
        WissenEinstellungen.Werte eingabe = werte == null ? null : new WissenEinstellungen.Werte(
                werte.budgetTokens(), werte.lintBudgetTokens(), werte.lintZeitplan(), werte.monatsbudgetTokens());
        return dto(einstellungen.speichern(benutzer(), eingabe));
    }

    private static WerteDto dto(WissenEinstellungen.Werte werte) {
        return new WerteDto(werte.budgetTokens(), werte.lintBudgetTokens(), werte.lintZeitplan(),
                werte.monatsbudgetTokens());
    }

    private Benutzer benutzer() {
        return new Benutzer(identitaet.getPrincipal().getName(), Rolle.ausRealmRollen(identitaet.getRoles())
                .orElseThrow());
    }
}
