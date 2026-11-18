package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.adapter.StrengesJson;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.konfiguration.application.RegelAenderung;
import ch.urinet.archiv.konfiguration.application.RegelVerwaltung;
import ch.urinet.archiv.konfiguration.domain.Regel;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Path("/regeln")
@Tag(name = "Konfiguration")
@Produces(MediaType.APPLICATION_JSON)
public class RegelResource {

    private static final int GRUND_MAX = 1000;
    private static final Pattern VERSION = Pattern.compile("^\\s*\"?(\\d+)\"?\\s*$");
    private static final Set<String> ANLAGEFELDER = Set.of("code", "art", "typ", "parameter", "schwere", "meldung",
            "aktiv", "grund");
    private static final Set<String> AENDERUNGSFELDER = Set.of("art", "typ", "parameter", "schwere", "meldung",
            "aktiv", "grund");
    private static final TypeReference<Map<String, Object>> PARAMETER_TYP = new TypeReference<>() {
    };

    private final RegelVerwaltung verwaltung;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;
    private final ObjectMapper jsonMapper;

    public RegelResource(RegelVerwaltung verwaltung, MandantAufloesung aufloesung, SecurityIdentity identitaet,
                         ObjectMapper jsonMapper) {
        this.verwaltung = verwaltung;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
        this.jsonMapper = jsonMapper;
    }

    @Schema(name = "Regel")
    public record RegelDto(String code, String art, String typ, Map<String, Object> parameter, String schwere,
                           String meldung, boolean aktiv,
                           @Schema(nullable = true, description = "Zahl bestätigter Dokumente, die die Regel verletzen")
                           Long betroffeneDokumente) {

        static RegelDto aus(Regel regel, Long betroffeneDokumente) {
            return new RegelDto(regel.code(), regel.art(), regel.typ(), regel.parameter(), regel.schwere(),
                    regel.meldung(), regel.aktiv(), betroffeneDokumente);
        }
    }

    @Schema(name = "RegelAnlegen")
    public record RegelAnlegenDto(
            @Schema(required = true, pattern = "^[a-z][a-z0-9_]{0,39}$") String code,
            @Schema(nullable = true) String art,
            @Schema(required = true, enumeration = {"PFLICHT_WENN", "EINDEUTIG", "FOLGE", "ERWARTET"}) String typ,
            @Schema(required = true, type = SchemaType.OBJECT) Map<String, Object> parameter,
            @Schema(required = true, enumeration = {"FEHLER", "WARNUNG"}) String schwere,
            @Schema(required = true) String meldung, Boolean aktiv,
            @Schema(nullable = true, maxLength = GRUND_MAX) String grund) {
    }

    @Schema(name = "RegelAenderung")
    public record RegelAenderungDto(@Schema(nullable = true) String art,
            @Schema(enumeration = {"PFLICHT_WENN", "EINDEUTIG", "FOLGE", "ERWARTET"}) String typ,
            @Schema(type = SchemaType.OBJECT) Map<String, Object> parameter,
            @Schema(enumeration = {"FEHLER", "WARNUNG"}) String schwere, String meldung, Boolean aktiv,
            @Schema(nullable = true, maxLength = GRUND_MAX) String grund) {
    }

    @GET
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Regeln des Mandanten auflisten")
    @APIResponse(responseCode = "200", description = "Aktive und inaktive Regeln",
            headers = @org.eclipse.microprofile.openapi.annotations.headers.Header(
                    name = "ETag", description = "Aktuelle Konfigurationsversion für If-Match"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(type = SchemaType.ARRAY, implementation = RegelDto.class)))
    public Response liste() {
        aufloesung.pruefen();
        return Response.ok(verwaltung.alle().stream().map(regel -> RegelDto.aus(regel, null)).toList())
                .tag(new EntityTag(Integer.toString(verwaltung.aktuelleVersion()))).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Dokumentregel anlegen", description = "If-Match mit Konfigurationsversion ist Pflicht.")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = RegelAnlegenDto.class)))
    @APIResponse(responseCode = "201", description = "Angelegt; betroffeneDokumente zählt bestätigte Verstösse.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = RegelDto.class)),
            headers = @org.eclipse.microprofile.openapi.annotations.headers.Header(
                    name = "ETag", description = "Neue Konfigurationsversion"))
    @APIResponse(responseCode = "403", description = "NICHT_ERLAUBT")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "422", description = "KONFIGURATION_UNGUELTIG")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response anlegen(@HeaderParam(HttpHeaders.IF_MATCH) @Parameter(required = true) String ifMatch,
                            String body) {
        aufloesung.pruefen();
        JsonNode objekt = objekt(body, ANLAGEFELDER);
        Regel neu = new Regel(erforderlichText(objekt, "code"), text(objekt, "art"), erforderlichText(objekt, "typ"),
                parameter(objekt.get("parameter")), erforderlichText(objekt, "schwere"),
                erforderlichText(objekt, "meldung"), optionalBool(objekt, "aktiv", true));
        RegelVerwaltung.Ergebnis ergebnis = verwaltung.anlegen(neu, version(ifMatch), wer(), grund(objekt));
        return Response.status(Response.Status.CREATED)
                .entity(RegelDto.aus(ergebnis.regel(), ergebnis.betroffeneDokumente()))
                .tag(new EntityTag(Integer.toString(ergebnis.version()))).build();
    }

    @PATCH
    @Path("/{code}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Dokumentregel ändern, aktivieren oder deaktivieren",
            description = "Code bleibt unverändert. If-Match mit Konfigurationsversion ist Pflicht.")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = RegelAenderungDto.class)))
    @APIResponse(responseCode = "200", description = "Geändert",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = RegelDto.class)),
            headers = @org.eclipse.microprofile.openapi.annotations.headers.Header(
                    name = "ETag", description = "Neue Konfigurationsversion"))
    @APIResponse(responseCode = "403", description = "NICHT_ERLAUBT")
    @APIResponse(responseCode = "404", description = "NICHT_GEFUNDEN")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "422", description = "KONFIGURATION_UNGUELTIG")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response aendern(@PathParam("code") String code,
                            @HeaderParam(HttpHeaders.IF_MATCH) @Parameter(required = true) String ifMatch,
                            String body) {
        aufloesung.pruefen();
        JsonNode objekt = objekt(body, AENDERUNGSFELDER);
        Set<String> gesetzt = gesetzteFelder(objekt, AENDERUNGSFELDER);
        gesetzt.remove("grund");
        if (gesetzt.isEmpty()) {
            throw ungueltig();
        }
        RegelAenderung aenderung = new RegelAenderung(gesetzt, text(objekt, "art"), text(objekt, "typ"),
                objekt.has("parameter") ? parameter(objekt.get("parameter")) : null, text(objekt, "schwere"),
                text(objekt, "meldung"), bool(objekt, "aktiv"));
        RegelVerwaltung.Ergebnis ergebnis = verwaltung.aendern(code, aenderung, version(ifMatch), wer(),
                grund(objekt));
        return Response.ok(RegelDto.aus(ergebnis.regel(), ergebnis.betroffeneDokumente()))
                .tag(new EntityTag(Integer.toString(ergebnis.version()))).build();
    }

    private String wer() {
        return identitaet.getPrincipal().getName();
    }

    private Map<String, Object> parameter(JsonNode knoten) {
        if (knoten == null || !knoten.isObject()) {
            throw ungueltig();
        }
        return jsonMapper.convertValue(knoten, PARAMETER_TYP);
    }

    private static JsonNode objekt(String text, Set<String> erlaubt) {
        JsonNode json;
        try {
            json = text == null ? null : StrengesJson.lesen(text);
        } catch (JsonProcessingException e) {
            throw ungueltig();
        }
        if (json == null || !json.isObject()) {
            throw ungueltig();
        }
        for (Iterator<String> namen = json.fieldNames(); namen.hasNext();) {
            if (!erlaubt.contains(namen.next())) {
                throw ungueltig();
            }
        }
        return json;
    }

    private static Set<String> gesetzteFelder(JsonNode json, Set<String> erlaubt) {
        Set<String> gesetzt = new HashSet<>();
        for (Iterator<String> namen = json.fieldNames(); namen.hasNext();) {
            gesetzt.add(namen.next());
        }
        if (!erlaubt.containsAll(gesetzt)) {
            throw ungueltig();
        }
        return gesetzt;
    }

    private static String erforderlichText(JsonNode json, String feld) {
        String wert = text(json, feld);
        if (wert == null || wert.isBlank()) {
            throw ungueltig();
        }
        return wert;
    }

    private static String text(JsonNode json, String feld) {
        JsonNode knoten = json.get(feld);
        if (knoten == null || knoten.isNull()) {
            return null;
        }
        if (!knoten.isTextual()) {
            throw ungueltig();
        }
        return knoten.textValue();
    }

    private static Boolean bool(JsonNode json, String feld) {
        JsonNode knoten = json.get(feld);
        if (knoten == null) {
            return null;
        }
        if (!knoten.isBoolean()) {
            throw ungueltig();
        }
        return knoten.booleanValue();
    }

    private static boolean optionalBool(JsonNode json, String feld, boolean standard) {
        Boolean wert = bool(json, feld);
        return wert == null ? standard : wert;
    }

    private static String grund(JsonNode json) {
        String grund = text(json, "grund");
        if (grund != null && (grund.length() > GRUND_MAX
                || grund.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL))) {
            throw ungueltig();
        }
        return grund == null ? null : grund.strip();
    }

    private static int version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new FachFehler(Fehlercode.VERSION_FEHLT);
        }
        Matcher treffer = VERSION.matcher(ifMatch);
        if (!treffer.matches()) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        try {
            return Integer.parseInt(treffer.group(1));
        } catch (NumberFormatException e) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
    }

    private static FachFehler ungueltig() {
        return new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }
}
