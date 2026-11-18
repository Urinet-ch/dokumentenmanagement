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
import ch.urinet.archiv.wissen.application.WissenIngestStarten;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import java.util.UUID;

/**
 * Manueller Ingest-Start (A16); der Mandant stammt ausschliesslich aus der verifizierten Anmeldung. A16 ist
 * dokumentbezogen: Der Zugangfilter prüft zuerst die Sichtbarkeit der Quelle (404), dann das Recht (403).
 */
@Path("/dokumente/{id}/wissen-ingest")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WissenIngestResource {

    private final WissenIngestStarten ingest;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public WissenIngestResource(WissenIngestStarten ingest, MandantAufloesung aufloesung,
                                SecurityIdentity identitaet) {
        this.ingest = ingest;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @Schema(name = "WissenIngestStart", description = "Optionaler PDF-Seitenbereich für einen manuellen Ingest.")
    public record StartDto(
            @Schema(nullable = true, minimum = "1") Integer seiteVon,
            @Schema(nullable = true, minimum = "1") Integer seiteBis) {
    }

    @Schema(name = "WissenIngestAuftrag", description = "Eingereihter Ingest-Entwurf.")
    public record AuftragDto(UUID entwurfId,
                             @Schema(enumeration = {"IN_ARBEIT", "WARTEND", "FEHLER"}) String status,
                             long geschaetzteTokens,
                             @Schema(nullable = true) String hinweis) {
    }

    @POST
    @ErfordertAktion(Aktion.A16)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.WISSEN_INGEST)
    @Operation(summary = "Ingest einer Wissensquelle starten",
            description = "Reiht den Ingest einer archivierten Wissensquelle ein. Optional kann ein PDF-Seitenbereich "
                    + "angegeben werden. Personalunterlagen, fehlender Volltext, fehlende PDF/A oder nicht passende "
                    + "Seitenangaben werden ohne KI-Aufruf als Entwurf mit Hinweis festgehalten.")
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = StartDto.class)))
    @APIResponse(responseCode = "202", description = "Ingest eingereiht oder mit Vorprüfungshinweis abgeschlossen",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = AuftragDto.class)))
    @APIResponse(responseCode = "400", description = "Seitenbereich ungültig")
    @APIResponse(responseCode = "403", description = "Rolle darf keinen Ingest starten")
    @APIResponse(responseCode = "404", description = "Dokument unbekannt oder nicht sichtbar")
    @APIResponse(responseCode = "409", description = "Dokument nicht archiviert, oder Ingest läuft oder wartet bereits")
    @APIResponse(responseCode = "422", description = "Dokument ist keine Wissensquelle")
    @APIResponse(responseCode = "429", description = "Monatsbudget der Wissensbasis erschöpft")
    public Response starten(@PathParam("id") UUID id, StartDto angaben) {
        aufloesung.pruefen();
        Benutzer benutzer = benutzer();
        WissenIngestStarten.Ergebnis ergebnis = ingest.manuell(id,
                angaben == null ? null : angaben.seiteVon(),
                angaben == null ? null : angaben.seiteBis(),
                benutzer.id());
        return Response.accepted(new AuftragDto(ergebnis.entwurfId(), ergebnis.status(),
                ergebnis.geschaetzteTokens(), ergebnis.hinweis())).build();
    }

    private Benutzer benutzer() {
        String id = identitaet.getPrincipal().getName();
        Rolle rolle = Rolle.ausRealmRollen(identitaet.getRoles())
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_ERLAUBT));
        return new Benutzer(id, rolle);
    }
}
