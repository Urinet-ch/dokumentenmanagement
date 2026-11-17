package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.dokumentart.adapter.StrengesJson;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.konfiguration.application.KlassifikationVerwaltung;
import ch.urinet.archiv.konfiguration.domain.ArtKlassifikation;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Path("/dokumentarten/{art}/klassifikationen")
@Tag(name = "Konfiguration")
@Produces(MediaType.APPLICATION_JSON)
public class ArtKlassifikationResource {

    private static final Pattern VERSION = Pattern.compile("^\\s*\"?(\\d+)\"?\\s*$");
    private static final Set<String> ANLAGEFELDER = Set.of("klassifikation", "pflicht", "standardWert", "grund");
    private static final Set<String> AENDERUNGSFELDER = Set.of("pflicht", "standardWert", "grund");

    private final KlassifikationVerwaltung verwaltung;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public ArtKlassifikationResource(KlassifikationVerwaltung verwaltung, MandantAufloesung aufloesung,
                                     SecurityIdentity identitaet) {
        this.verwaltung = verwaltung;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @Schema(name = "ArtKlassifikation")
    public record ZuordnungDto(String klassifikation, boolean pflicht, String standardWert) {
        static ZuordnungDto aus(ArtKlassifikation zuordnung) {
            return new ZuordnungDto(zuordnung.klassifikation(), zuordnung.pflicht(), zuordnung.standardWert());
        }
    }

    @GET
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Klassifikationen einer Dokumentart auflisten")
    public Response liste(@PathParam("art") String art) {
        aufloesung.pruefen();
        return Response.ok(verwaltung.artKlassifikationen(art).stream().map(ZuordnungDto::aus).toList())
                .tag(new EntityTag(Integer.toString(verwaltung.aktuelleVersion()))).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Klassifikation einer Dokumentart zuordnen")
    @APIResponse(responseCode = "201", description = "Zugeordnet")
    @APIResponse(responseCode = "422", description = "KONFIGURATION_UNGUELTIG")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response anlegen(@PathParam("art") String art, @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
                            String body) {
        aufloesung.pruefen();
        JsonNode json = objekt(body, ANLAGEFELDER);
        ArtKlassifikation zuordnung = new ArtKlassifikation(klassifikation(json), bool(json, "pflicht"),
                text(json, "standardWert"));
        ArtKlassifikation gespeichert = verwaltung.artKlassifikationAnlegen(art, zuordnung, version(ifMatch), wer(),
                grund(json));
        return Response.status(Response.Status.CREATED).entity(ZuordnungDto.aus(gespeichert)).tag(neueVersion())
                .build();
    }

    @PATCH
    @Path("/{code}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Zuordnung ändern")
    @APIResponse(responseCode = "200", description = "Geändert")
    @APIResponse(responseCode = "422", description = "KONFIGURATION_UNGUELTIG")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response aendern(@PathParam("art") String art, @PathParam("code") String code,
                            @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch, String body) {
        aufloesung.pruefen();
        JsonNode json = objekt(body, AENDERUNGSFELDER);
        if (json.size() == 0 || json.has("grund") && json.size() == 1) {
            throw ungueltig();
        }
        ArtKlassifikation gespeichert = verwaltung.artKlassifikationAendern(art, code,
                json.has("pflicht") ? bool(json, "pflicht") : verwaltung.artKlassifikationen(art).stream()
                        .filter(zuordnung -> zuordnung.klassifikation().equals(code)).findFirst()
                        .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN)).pflicht(),
                text(json, "standardWert"), json.has("standardWert"), version(ifMatch), wer(), grund(json));
        return Response.ok(ZuordnungDto.aus(gespeichert)).tag(neueVersion()).build();
    }

    @DELETE
    @Path("/{code}")
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Klassifikation einer Dokumentart entfernen")
    @APIResponse(responseCode = "204", description = "Entfernt")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response loeschen(@PathParam("art") String art, @PathParam("code") String code,
                             @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch) {
        aufloesung.pruefen();
        verwaltung.artKlassifikationLoeschen(art, code, version(ifMatch), wer(), null);
        return Response.noContent().tag(neueVersion()).build();
    }

    private EntityTag neueVersion() {
        return new EntityTag(Integer.toString(verwaltung.aktuelleVersion()));
    }

    private String wer() {
        return identitaet.getPrincipal().getName();
    }

    private static String klassifikation(JsonNode json) {
        JsonNode wert = json.get("klassifikation");
        if (wert == null || !wert.isTextual() || wert.textValue().isBlank()) {
            throw ungueltig();
        }
        return wert.textValue();
    }

    private static boolean bool(JsonNode json, String feld) {
        JsonNode wert = json.get(feld);
        if (wert == null || !wert.isBoolean()) {
            throw ungueltig();
        }
        return wert.booleanValue();
    }

    private static String text(JsonNode json, String feld) {
        JsonNode wert = json.get(feld);
        if (wert == null || wert.isNull()) {
            return null;
        }
        if (!wert.isTextual()) {
            throw ungueltig();
        }
        return wert.textValue();
    }

    private static String grund(JsonNode json) {
        String grund = text(json, "grund");
        if (grund != null && grund.length() > 1000) {
            throw ungueltig();
        }
        return grund == null ? null : grund.strip();
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
