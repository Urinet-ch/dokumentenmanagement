package ch.urinet.archiv.dokumentart.adapter.in.rest;

import ch.urinet.archiv.dokumentart.application.DokumentartAngaben;
import ch.urinet.archiv.dokumentart.application.DokumentartAngaben.Feld;
import ch.urinet.archiv.dokumentart.application.DokumentartVerwaltung;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
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
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Dokumentarten des eigenen Mandanten (Req 9.1, 9.4, 9.5): lesen alle Archiv-Rollen, pflegen nur {@code inhaber}
 * (A11, Req 2.17). Der Mandant kommt nur aus der Anmeldung; jede Anfrage arbeitet im Schema dieses Mandanten.
 * Unbekannte Felder im Body lehnt die Resource ab (400), statt sie still zu übergehen.
 */
@Path("/dokumentarten")
@Tag(name = "Dokumentarten")
@Produces(MediaType.APPLICATION_JSON)
public class DokumentartResource {

    private static final Map<String, Feld> FELDER = Map.ofEntries(
            Map.entry("code", Feld.CODE), Map.entry("name", Feld.NAME), Map.entry("beschreibung", Feld.BESCHREIBUNG),
            Map.entry("gruppen", Feld.GRUPPEN), Map.entry("pflichtfelder", Feld.PFLICHTFELDER),
            Map.entry("zusatzSchema", Feld.ZUSATZ_SCHEMA), Map.entry("vertraulichkeit", Feld.VERTRAULICHKEIT),
            Map.entry("aufbewahrungJahre", Feld.AUFBEWAHRUNG_JAHRE),
            Map.entry("aufbewahrungAb", Feld.AUFBEWAHRUNG_AB),
            Map.entry("gesetzlichePflicht", Feld.GESETZLICHE_PFLICHT), Map.entry("aktiv", Feld.AKTIV));

    private final DokumentartVerwaltung verwaltung;
    private final MandantAufloesung aufloesung;
    private final ObjectMapper json;

    public DokumentartResource(DokumentartVerwaltung verwaltung, MandantAufloesung aufloesung, ObjectMapper json) {
        this.verwaltung = verwaltung;
        this.aufloesung = aufloesung;
        this.json = json;
    }

    @Schema(name = "Dokumentart")
    public record DokumentartDto(
            @Schema(required = true, pattern = "^[A-Z][A-Z0-9_]{1,39}$") String code,
            @Schema(required = true, maxLength = Dokumentart.NAME_MAX) String name,
            @Schema(nullable = true, maxLength = Dokumentart.BESCHREIBUNG_MAX,
                    description = "Abgrenzung zu ähnlichen Arten; geht in den KI-Prompt (Req 9.2)") String beschreibung,
            @Schema(required = true, description = "Teilmenge von finanz, vertrag, frei") List<String> gruppen,
            @Schema(required = true, description = "Felder des Dokuments oder zusatz.<feld>") List<String> pflichtfelder,
            @Schema(required = true, type = SchemaType.OBJECT,
                    description = "JSON Schema Draft 2020-12 der Zusatzfelder") JsonNode zusatzSchema,
            @Schema(required = true, enumeration = {"NORMAL", "INTERN", "PERSONAL"}) String vertraulichkeit,
            @Schema(nullable = true, minimum = "1", maximum = "100", description = "null = unbefristet")
            Integer aufbewahrungJahre,
            @Schema(nullable = true, enumeration = {"GESCHAEFTSJAHR", "GUELTIG_BIS"})
            String aufbewahrungAb,
            @Schema(required = true) boolean gesetzlichePflicht,
            @Schema(required = true) boolean aktiv) {
    }

    @Schema(name = "DokumentartAenderung",
            description = "Beim Anlegen sind code, name, vertraulichkeit, gesetzlichePflicht und aufbewahrungJahre "
                    + "Pflicht; beim Ändern ersetzen die angegebenen Felder die bisherigen. Unbekannte Felder → 400.")
    public record DokumentartAenderungDto(String code, String name, @Schema(nullable = true) String beschreibung,
            List<String> gruppen, List<String> pflichtfelder,
            @Schema(type = SchemaType.OBJECT) JsonNode zusatzSchema,
            @Schema(enumeration = {"NORMAL", "INTERN", "PERSONAL"}) String vertraulichkeit,
            @Schema(nullable = true, minimum = "1", maximum = "100") Integer aufbewahrungJahre,
            @Schema(nullable = true, enumeration = {"GESCHAEFTSJAHR", "GUELTIG_BIS"})
            String aufbewahrungAb,
            Boolean gesetzlichePflicht, Boolean aktiv) {
    }

    @GET
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Dokumentarten des Mandanten auflisten (aktive und inaktive)")
    public List<DokumentartDto> liste() {
        aufloesung.pruefen();
        return verwaltung.alle().stream().map(this::dto).toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Dokumentart anlegen",
            description = "Ungültiges Zusatz-Schema → 422 ZUSATZ_UNGUELTIG; vergebener Code → 400.")
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = DokumentartAenderungDto.class)))
    @APIResponse(responseCode = "201", description = "Angelegt", content = @Content(
            mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = DokumentartDto.class)))
    public Response anlegen(JsonNode body) {
        aufloesung.pruefen();
        Dokumentart art = verwaltung.anlegen(angaben(body));
        return Response.status(Response.Status.CREATED).entity(dto(art)).build();
    }

    @PATCH
    @Path("/{code}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Dokumentart ändern oder deaktivieren",
            description = "Angegebene Felder ersetzen die bisherigen; der Code bleibt. aktiv=false deaktiviert.")
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = DokumentartAenderungDto.class)))
    public DokumentartDto aendern(@PathParam("code") String code, JsonNode body) {
        aufloesung.pruefen();
        return dto(verwaltung.aendern(code, angaben(body)));
    }

    @DELETE
    @Path("/{code}")
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Dokumentart löschen",
            description = "Nur ohne Dokumente (auch verworfene); sonst 409 DOKUMENTART_IN_GEBRAUCH.")
    @APIResponse(responseCode = "204", description = "Gelöscht")
    public void loeschen(@PathParam("code") String code) {
        aufloesung.pruefen();
        verwaltung.loeschen(code);
    }

    private DokumentartDto dto(Dokumentart art) {
        JsonNode schema;
        try {
            schema = json.readTree(art.zusatzSchema());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("zusatz_schema der Art " + art.code() + " ist kein JSON", e);
        }
        return new DokumentartDto(art.code(), art.name(), art.beschreibung(), art.gruppen(), art.pflichtfelder(),
                schema, art.vertraulichkeit().name(), art.aufbewahrung().jahre(),
                art.aufbewahrung().ab() == null ? null : art.aufbewahrung().ab().name(), art.gesetzlichePflicht(),
                art.aktiv());
    }

    private static DokumentartAngaben angaben(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ungueltig();
        }
        Set<Feld> gesetzt = EnumSet.noneOf(Feld.class);
        for (Iterator<String> namen = body.fieldNames(); namen.hasNext(); ) {
            Feld feld = FELDER.get(namen.next());
            if (feld == null) {
                throw ungueltig();
            }
            gesetzt.add(feld);
        }
        return new DokumentartAngaben(gesetzt, text(body, "code"), text(body, "name"), text(body, "beschreibung"),
                liste(body, "gruppen"), liste(body, "pflichtfelder"),
                wert(body, "zusatzSchema", JsonNode::toString, n -> true),
                text(body, "vertraulichkeit"),
                wert(body, "aufbewahrungJahre", JsonNode::intValue, JsonNode::isInt),
                text(body, "aufbewahrungAb"),
                wert(body, "gesetzlichePflicht", JsonNode::booleanValue, JsonNode::isBoolean),
                wert(body, "aktiv", JsonNode::booleanValue, JsonNode::isBoolean));
    }

    private static String text(JsonNode body, String name) {
        return wert(body, name, JsonNode::textValue, JsonNode::isTextual);
    }

    private static List<String> liste(JsonNode body, String name) {
        return wert(body, name, n -> {
            List<String> werte = new ArrayList<>();
            for (JsonNode element : n) {
                if (!element.isTextual()) {
                    throw ungueltig();
                }
                werte.add(element.textValue());
            }
            return werte;
        }, JsonNode::isArray);
    }

    /** Fehlendes Feld oder {@code null} ergibt {@code null}; ein Wert vom falschen Typ ist 400. */
    private static <T> T wert(JsonNode body, String name, Function<JsonNode, T> lesen,
                              Predicate<JsonNode> typ) {
        JsonNode knoten = body.get(name);
        if (knoten == null || knoten.isNull()) {
            return null;
        }
        if (!typ.test(knoten)) {
            throw ungueltig();
        }
        return lesen.apply(knoten);
    }

    private static FachFehler ungueltig() {
        return new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }
}
