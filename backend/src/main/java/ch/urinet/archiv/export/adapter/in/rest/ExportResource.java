package ch.urinet.archiv.export.adapter.in.rest;

import ch.urinet.archiv.export.application.ExportVerwaltung;
import ch.urinet.archiv.export.domain.Export;
import ch.urinet.archiv.export.domain.ExportStatus;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
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
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Jahresexport für Inhaber und Treuhänder (Spec 02, Req 7.1–7.5; design.md «Jahresexport»). Die Rechtematrix
 * (Spec 01, Req 2.6) hat für den Export keine Zeile: Die Endpunkte sind darum für alle Archiv-Rollen erklärt
 * ({@link AlleArchivRollen}, Mandant und Rolle prüft der Filter), und {@link ExportVerwaltung} lässt nur
 * {@code inhaber} und {@code treuhaender} zu, sonst 403 mit Eintrag {@code VERWEIGERT}. Die Download-Adresse ist ein
 * Zugangsschlüssel für 24 Stunden: Antworten mit Adresse werden nicht zwischengespeichert.
 */
@Path("/exporte")
@Tag(name = "Exporte")
@Produces(MediaType.APPLICATION_JSON)
public class ExportResource {

    private final ExportVerwaltung verwaltung;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;
    private final Clock uhr = Clock.systemUTC();

    public ExportResource(ExportVerwaltung verwaltung, MandantAufloesung aufloesung, SecurityIdentity identitaet) {
        this.verwaltung = verwaltung;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @Schema(name = "ExportAnfrage")
    public record ExportAnfrageDto(@Schema(required = true, minimum = "1900", maximum = "2999",
            description = "Geschäftsjahr, bezeichnet nach dem Kalenderjahr seines Endes") Integer geschaeftsjahr) {
    }

    @Schema(name = "Export", description = "Jahresexport; status ABGELAUFEN heisst fertig, aber der Download-Link "
            + "(24 Stunden ab Fertigstellung) gilt nicht mehr")
    public record ExportDto(
            @Schema(required = true) UUID id,
            @Schema(required = true) int geschaeftsjahr,
            @Schema(required = true, enumeration = {"OFFEN", "FERTIG", "ABGELAUFEN", "FEHLER"}) String status,
            @Schema(required = true) Instant erstellt,
            @Schema(nullable = true) Instant fertig,
            @Schema(nullable = true, description = "Ende der Gültigkeit des Download-Links") Instant gueltigBis,
            @Schema(nullable = true, description = "Zahl der Belege (Dokumente)") Integer anzahl,
            @Schema(nullable = true, description = "Grösse des ZIP in Byte") Long groesse,
            @Schema(nullable = true, description = "SHA-256 des ZIP (hex)") String sha256,
            @Schema(nullable = true, description = "vorsignierte Adresse des ZIP, nur bei GET /exporte/{id} für einen "
                    + "fertigen, nicht abgelaufenen Export") String url) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Jahresexport anfordern",
            description = "Nur inhaber und treuhaender (Req 7.1, 7.5), sonst 403 NICHT_ERLAUBT ohne Auftrag. Reiht den "
                    + "Auftrag EXPORT_JAHR ein und protokolliert EXPORTIEREN. Ungültiges Geschäftsjahr → 400; läuft "
                    + "im Mandanten schon ein Export → 409 STATUS_KONFLIKT.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ExportAnfrageDto.class)))
    @APIResponse(responseCode = "202", description = "Export angelegt und eingereiht",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ExportDto.class)))
    public Response anfordern(ExportAnfrageDto anfrage) {
        aufloesung.pruefen();
        Export export = verwaltung.anfordern(benutzer(), anfrage == null ? null : anfrage.geschaeftsjahr());
        return Response.status(Response.Status.ACCEPTED).entity(dto(export, null)).build();
    }

    @GET
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Jahresexporte", description = "Die neuesten 20, neueste zuerst, ohne Download-Adresse. Nur "
            + "inhaber und treuhaender, sonst 403 NICHT_ERLAUBT.")
    public List<ExportDto> liste() {
        aufloesung.pruefen();
        return verwaltung.liste(benutzer()).stream().map(e -> dto(e, null)).toList();
    }

    @GET
    @Path("/{id}")
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Jahresexport abrufen",
            description = "Bei einem fertigen Export mit der vorsignierten Adresse des ZIP, gültig bis 24 Stunden nach "
                    + "der Fertigstellung (Req 7.2); jede ausgegebene Adresse protokolliert HERUNTERLADEN. Nur "
                    + "inhaber und treuhaender, sonst 403 NICHT_ERLAUBT; unbekannt → 404 NICHT_GEFUNDEN.")
    public Response abrufen(@PathParam("id") String id) {
        MandantCode mandant = aufloesung.pruefen();
        UUID exportId;
        try {
            exportId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        ExportVerwaltung.Abruf abruf = verwaltung.abrufen(benutzer(), mandant, exportId);
        String url = abruf.adresse().map(a -> a.url().toString()).orElse(null);
        return Response.ok(dto(abruf.export(), url)).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private ExportDto dto(Export e, String url) {
        String status = e.status() == ExportStatus.FERTIG && !e.herunterladbar(uhr.instant()) ? "ABGELAUFEN"
                : e.status().name();
        return new ExportDto(e.id(), e.geschaeftsjahr(), status, e.erstellt(), e.fertig().orElse(null),
                e.gueltigBis().orElse(null), e.anzahl().orElse(null), e.groesse().orElse(null),
                e.sha256().orElse(null), url);
    }

    /** Der ZugangFilter hat Rolle und Benutzer-ID schon geprüft (sonst 403). */
    private Benutzer benutzer() {
        return new Benutzer(identitaet.getPrincipal().getName(),
                Rolle.ausRealmRollen(identitaet.getRoles()).orElseThrow());
    }
}
