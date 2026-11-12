package ch.urinet.archiv.dokument.adapter.in.rest;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.dokument.application.DokumentDatei;
import ch.urinet.archiv.dokument.application.DokumentLesen;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Dokument lesen und herunterladen (A2–A4, Req 1.4, 2.3, 2.21, 14.6); den Eingangskorb liest die
 * {@link EingangskorbResource}, die Suche die {@code SucheResource}. Sichtbarkeit
 * und Recht prüft der {@code ZugangFilter} vor dem Lesen (404 vor 403); die Abfragen filtern zusätzlich nach
 * Vertraulichkeit. Ändern, Bestätigen, Verwerfen und die Oberfläche folgen mit Welle 3.
 */
@Path("/dokumente")
@Tag(name = "Dokumente")
@Produces(MediaType.APPLICATION_JSON)
public class DokumentResource {

    private final DokumentLesen lesen;
    private final DokumentDatei datei;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;
    private final ObjectMapper json;

    public DokumentResource(DokumentLesen lesen, DokumentDatei datei, MandantAufloesung aufloesung,
                            SecurityIdentity identitaet, ObjectMapper json) {
        this.lesen = lesen;
        this.datei = datei;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
        this.json = json;
    }

    @Schema(name = "Dokument", description = "Metadaten eines sichtbaren Dokuments")
    public record DokumentDto(
            @Schema(required = true) UUID id,
            @Schema(nullable = true, description = "Code der Dokumentart; null, solange nicht erkannt") String art,
            @Schema(nullable = true) String titel,
            @Schema(nullable = true) LocalDate datum,
            @Schema(required = true) Instant eingang,
            @Schema(required = true, enumeration = {"SCAN", "UPLOAD", "GENERATOR", "DRIVE", "EMAIL", "ABANINJA"})
            String quelle,
            @Schema(required = true) List<String> tags,
            @Schema(required = true, enumeration = {"NORMAL", "INTERN", "PERSONAL"},
                    description = "Ohne bestätigte Art gilt PERSONAL (Req 2.21)") String vertraulichkeit,
            @Schema(required = true, enumeration = {"NEU", "VERARBEITUNG", "EINGANGSKORB", "ARCHIVIERUNG",
                    "ARCHIVIERUNG_FEHLER", "ARCHIVIERT", "FEHLER", "VERWORFEN"}) String status,
            @Schema(nullable = true) Instant bestaetigt,
            @Schema(nullable = true) Integer geschaeftsjahr,
            @Schema(nullable = true) LocalDate aufbewahrenBis,
            @Schema(nullable = true) LocalDate sperreBis,
            @Schema(required = true) boolean fristAbgelaufen,
            @Schema(nullable = true, type = SchemaType.OBJECT, description = "Zusatzfelder nach dem Schema der Art")
            JsonNode zusatz,
            @Schema(required = true, description = "Version für If-Match (auch als ETag)") int version) {
    }

    @GET
    @Path("/{id}")
    @ErfordertAktion(Aktion.A2)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.ANZEIGEN)
    @Operation(summary = "Metadaten eines Dokuments lesen",
            description = "Unsichtbare und fremde Dokumente → 404 NICHT_GEFUNDEN (Req 1.4, 2.3).")
    @APIResponse(responseCode = "200", description = "Dokument",
            headers = @Header(name = "ETag", description = "Version des Dokuments für If-Match"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = DokumentDto.class)))
    public Response dokument(@PathParam("id") UUID id) {
        aufloesung.pruefen();
        Dokument dokument = lesen.lesen(benutzer(identitaet), id);
        return Response.ok(dto(dokument, json)).tag(new EntityTag(Integer.toString(dokument.version()))).build();
    }

    @GET
    @Path("/{id}/datei")
    @ErfordertAktion(Aktion.A2)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.HERUNTERLADEN)
    @Operation(summary = "Datei eines Dokuments herunterladen",
            description = "Antwortet mit 302 auf eine vorsignierte URL für genau die gespeicherte Version, höchstens "
                    + "5 Minuten gültig (Req 14.6). variante pdfa (Standard, ohne nr), original oder arbeitsfassung "
                    + "(nr ab 1, Standard 1). Unsichtbare, verworfene oder fehlende Dateien → 404 NICHT_GEFUNDEN.")
    @APIResponse(responseCode = "302", description = "Weiterleitung auf die vorsignierte URL",
            headers = @Header(name = "Location", description = "vorsignierte URL, nur für GET"))
    public Response datei(@PathParam("id") UUID id,
                          @QueryParam("variante") @Parameter(schema = @Schema(type = SchemaType.STRING,
                                  enumeration = {"pdfa", "original", "arbeitsfassung"}, defaultValue = "pdfa"))
                          String variante,
                          @QueryParam("nr") @Parameter(description = "Nummer der Datei (nur original und "
                                  + "arbeitsfassung)", schema = @Schema(type = SchemaType.INTEGER, format = "int32",
                                  minimum = "1", maximum = "9999")) String nr) {
        MandantCode mandant = aufloesung.pruefen();
        DateiRolle rolle = rolle(variante);
        int nummer = nummer(rolle, nr);
        VorsignierteUrl url = datei.herunterladen(benutzer(identitaet), mandant, id, rolle, nummer);
        return Response.status(Response.Status.FOUND).location(url.url())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static DateiRolle rolle(String variante) {
        return switch (variante == null || variante.isEmpty() ? "pdfa" : variante) {
            case "pdfa" -> DateiRolle.PDFA;
            case "original" -> DateiRolle.ORIGINAL;
            case "arbeitsfassung" -> DateiRolle.ARBEITSFASSUNG;
            default -> throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        };
    }

    private static int nummer(DateiRolle rolle, String nr) {
        boolean ohne = nr == null || nr.isEmpty();
        if (rolle == DateiRolle.PDFA) {
            if (!ohne) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            return 0;
        }
        if (ohne) {
            return 1;
        }
        try {
            int nummer = Integer.parseInt(nr);
            if (nummer >= 1 && nummer <= 9999) {
                return nummer;
            }
        } catch (NumberFormatException e) {
            // unten als ungültig gemeldet
        }
        throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }

    /** Der ZugangFilter hat Rolle und Benutzer-ID schon geprüft (sonst 403). */
    static Benutzer benutzer(SecurityIdentity identitaet) {
        return new Benutzer(identitaet.getPrincipal().getName(),
                Rolle.ausRealmRollen(identitaet.getRoles()).orElseThrow());
    }

    static DokumentDto dto(Dokument d, ObjectMapper json) {
        return new DokumentDto(d.id(), d.art(), d.titel(), d.datum(), d.eingang(), d.quelle(), d.tags(),
                d.wirksameVertraulichkeit(), d.status().name(), d.bestaetigt(), d.geschaeftsjahr(), d.aufbewahrenBis(),
                d.sperreBis(), d.fristAbgelaufen(), zusatz(d, json), d.version());
    }

    private static JsonNode zusatz(Dokument d, ObjectMapper json) {
        if (d.zusatz() == null) {
            return null;
        }
        try {
            return json.readTree(d.zusatz());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("zusatz des Dokuments " + d.id() + " ist kein JSON", e);
        }
    }
}
