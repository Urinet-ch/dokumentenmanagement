package ch.urinet.archiv.wissen.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenExportRepository;
import ch.urinet.archiv.wissen.application.WissenExportVerwaltung;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Path("/wissensbasis/exporte")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Wissensbasis")
public class WissenExportResource {
    private final WissenExportVerwaltung verwaltung;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public WissenExportResource(WissenExportVerwaltung verwaltung, MandantAufloesung aufloesung,
                                SecurityIdentity identitaet) {
        this.verwaltung = verwaltung;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @Schema(name = "WissenExport")
    public record ExportDto(UUID id, String status, Instant erstellt, @Schema(nullable = true) Instant fertig,
                            @Schema(nullable = true) Instant gueltigBis, @Schema(nullable = true) Long groesse,
                            @Schema(nullable = true) String sha256, @Schema(nullable = true) String url,
                            @Schema(nullable = true) UUID fehlerId) {
    }

    @POST
    @ErfordertAktion(Aktion.A19)
    @RolesAllowed("inhaber")
    @Operation(summary = "OKF-Export anfordern", description = "Nur Inhaber (A19); reiht WISSEN_EXPORT ein.")
    @APIResponse(responseCode = "202", description = "Export angelegt und eingereiht",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ExportDto.class)))
    public Response anfordern() {
        aufloesung.pruefen();
        return Response.accepted(dto(verwaltung.anfordern(benutzer()), null)).build();
    }

    @GET
    @Path("/{id}")
    @ErfordertAktion(Aktion.A19)
    @RolesAllowed("inhaber")
    @Operation(summary = "OKF-Export abrufen", description = "Nur Inhaber (A19); Download ab Abschluss für 24 Stunden.")
    @APIResponse(responseCode = "200", description = "Exportstatus und gegebenenfalls Download-Adresse",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ExportDto.class)))
    @APIResponse(responseCode = "404", description = "Export nicht gefunden")
    public Response abrufen(@PathParam("id") String id) {
        MandantCode mandant = aufloesung.pruefen();
        UUID exportId;
        try {
            exportId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        WissenExportVerwaltung.Abruf abruf = verwaltung.abrufen(benutzer(), mandant, exportId);
        return Response.ok(dto(abruf.export(), abruf.adresse().map(a -> a.url().toString()).orElse(null)))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private ExportDto dto(WissenExportRepository.Export export, String url) {
        boolean abgelaufen = export.status() == WissenExportRepository.Status.FERTIG
                && !export.herunterladbar(Instant.now());
        return new ExportDto(export.id(), abgelaufen ? "ABGELAUFEN" : export.status().name(), export.erstellt(),
                export.fertig(), export.fertig() == null ? null : export.fertig().plus(Duration.ofHours(24)),
                export.groesse(), export.sha256(), url, export.fehlerId());
    }

    private Benutzer benutzer() {
        return new Benutzer(identitaet.getPrincipal().getName(), Rolle.ausRealmRollen(identitaet.getRoles())
                .orElseThrow());
    }
}
