package ch.urinet.archiv.stapel.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.adapter.StrengesJson;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.stapel.application.TrennblattService;
import ch.urinet.archiv.stapel.domain.TrennblattCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Trennblatt-Generator (Spec 09, Req 6.1–6.3, 6.5; design.md «Trennblatt-Generator»). Trennblätter erzeugen gehört
 * zum Recht A1 (Spec 09, Req 15.1). Das PDF wird nicht im Archiv abgelegt und berührt kein Dokument; darum gibt es
 * keinen Eintrag {@code ERFASSEN} (Spec 01, design.md «Protokoll und Manifest»). Der Körper ist vor der Ressource auf
 * {@code ProblemFehlerRoute.TRENNBLAETTER_MAX_BYTES} begrenzt (413 {@code DATEI_ZU_GROSS}), damit die Grenze von 100
 * Blättern nicht erst nach dem Aufbau eines grossen JSON-Baums greift.
 */
@Path("/trennblaetter")
@Tag(name = "Trennblaetter")
public class TrennblattResource {

    static final String PDF = "application/pdf";
    private static final Set<String> FELDER = Set.of("typ", "art", "titel", "anzahl");

    private final TrennblattService service;
    private final MandantAufloesung aufloesung;

    public TrennblattResource(TrennblattService service, MandantAufloesung aufloesung) {
        this.service = service;
        this.aufloesung = aufloesung;
    }

    @Schema(name = "TrennblattAnfrage")
    public record AnfrageDto(@Schema(required = true, minItems = 1, maxItems = TrennblattService.MAX_BLAETTER,
            description = "Sätze in der Reihenfolge des PDF; zusammen höchstens 100 Blätter") List<WunschDto> blaetter) {
    }

    @Schema(name = "TrennblattWunsch")
    public record WunschDto(
            @Schema(required = true, enumeration = {"TRENN", "ORDNER"},
                    description = "TRENN = Trennblatt, ORDNER = Ordner-Deckblatt") String typ,
            @Schema(nullable = true, description = "nur bei TRENN: Code einer aktiven Dokumentart; fehlt er, ist es "
                    + "ein Trennblatt ohne Art") String art,
            @Schema(nullable = true, maxLength = TrennblattCode.TITEL_MAX,
                    description = "nur bei ORDNER, Pflicht: Titel des Deckblatts") String titel,
            @Schema(required = true, minimum = "1", maximum = "100", description = "Zahl der Blätter") int anzahl) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(PDF)
    @ErfordertAktion(Aktion.A1)
    @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle"})
    @Operation(summary = "Trennblätter und Ordner-Deckblätter als PDF",
            description = "A4 für beidseitigen Druck, je Blatt zwei gleiche Seiten mit QR-Code (Spec 09, Req 6.1–6.3). "
                    + "Nicht im Archiv abgelegt. Recht A1.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnfrageDto.class)))
    @APIResponse(responseCode = "200", description = "PDF zum Herunterladen",
            content = @Content(mediaType = PDF, schema = @Schema(type = SchemaType.STRING, format = "binary")))
    @APIResponse(responseCode = "400", description = "TRENNBLATT_UNGUELTIG: Titel fehlt, ist länger als 60 Zeichen "
            + "oder enthält Steuer- oder Formatzeichen (Req 6.5); ANFRAGE_UNGUELTIG: Format, Anzahl, mehr als 100 "
            + "Blätter oder Art keine aktive Dokumentart")
    @APIResponse(responseCode = "403", description = "NICHT_ERLAUBT: ohne Recht A1")
    @APIResponse(responseCode = "413", description = "DATEI_ZU_GROSS: Anfrage über 64 KiB")
    public Response erzeugen(String body) {
        aufloesung.pruefen();
        byte[] pdf = service.erzeugen(wuensche(body));
        return Response.ok(pdf, PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"trennblaetter.pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    static List<TrennblattService.Wunsch> wuensche(String body) {
        JsonNode blaetter;
        try {
            // streng: doppelte Schlüssel und Zeichen nach dem JSON-Wert sind ungültig
            JsonNode wurzel = body == null || body.isBlank() ? null : StrengesJson.lesen(body);
            blaetter = wurzel == null || !wurzel.isObject() || wurzel.size() != 1 ? null : wurzel.get("blaetter");
        } catch (JsonProcessingException e) {
            throw ungueltig();
        }
        if (blaetter == null || !blaetter.isArray() || blaetter.isEmpty()
                || blaetter.size() > TrennblattService.MAX_BLAETTER) {
            throw ungueltig();
        }
        List<TrennblattService.Wunsch> wuensche = new ArrayList<>();
        for (JsonNode blatt : blaetter) {
            if (!blatt.isObject()) {
                throw ungueltig();
            }
            for (Iterator<String> namen = blatt.fieldNames(); namen.hasNext(); ) {
                if (!FELDER.contains(namen.next())) {
                    throw ungueltig();
                }
            }
            JsonNode anzahl = blatt.path("anzahl");
            if (!anzahl.isIntegralNumber() || !anzahl.canConvertToInt()) {
                throw ungueltig();
            }
            wuensche.add(new TrennblattService.Wunsch(typ(blatt.path("typ")), text(blatt.path("art")),
                    text(blatt.path("titel")), anzahl.intValue()));
        }
        return wuensche;
    }

    private static TrennblattCode.Typ typ(JsonNode typ) {
        try {
            return TrennblattCode.Typ.valueOf(typ.isTextual() ? typ.textValue() : "");
        } catch (IllegalArgumentException e) {
            throw ungueltig();
        }
    }

    /** Fehlt das Feld oder ist es {@code null}: {@code null}; sonst nur Text. */
    private static String text(JsonNode feld) {
        if (feld.isMissingNode() || feld.isNull()) {
            return null;
        }
        if (!feld.isTextual()) {
            throw ungueltig();
        }
        return feld.textValue();
    }

    private static FachFehler ungueltig() {
        return new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }
}
