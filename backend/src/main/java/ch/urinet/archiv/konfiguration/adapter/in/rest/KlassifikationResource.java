package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.adapter.StrengesJson;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.konfiguration.application.KlassifikationAenderung;
import ch.urinet.archiv.konfiguration.application.KlassifikationVerwaltung;
import ch.urinet.archiv.konfiguration.application.KlassifikationWertAenderung;
import ch.urinet.archiv.konfiguration.domain.Klassifikation;
import ch.urinet.archiv.konfiguration.domain.KlassifikationWert;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Path("/klassifikationen")
@Tag(name = "Konfiguration")
@Produces(MediaType.APPLICATION_JSON)
public class KlassifikationResource {

    private static final int GRUND_MAX = 1000;
    private static final Pattern VERSION = Pattern.compile("^\\s*\"?(\\d+)\"?\\s*$");
    private static final Set<String> ANLAGEFELDER = Set.of("code", "name", "beschreibung", "werte", "mehrfach",
            "kiVorschlag", "suchfilter", "oeffentlichFilter", "aktiv", "grund");
    private static final Set<String> AENDERUNGSFELDER = Set.of("code", "name", "beschreibung", "mehrfach",
            "kiVorschlag", "suchfilter", "oeffentlichFilter", "aktiv", "grund");
    private static final Set<String> WERTFELDER = Set.of("code", "name", "beschreibung", "reihenfolge", "aktiv",
            "grund");
    private static final Set<String> WERTAENDERUNGSFELDER = Set.of("code", "name", "beschreibung", "reihenfolge",
            "aktiv", "grund");

    private final KlassifikationVerwaltung verwaltung;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public KlassifikationResource(KlassifikationVerwaltung verwaltung, MandantAufloesung aufloesung,
                                  SecurityIdentity identitaet) {
        this.verwaltung = verwaltung;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @Schema(name = "KlassifikationWert")
    public record WertDto(String code, String name, String beschreibung, int reihenfolge, boolean aktiv) {
        static WertDto aus(KlassifikationWert wert) {
            return new WertDto(wert.code(), wert.name(), wert.beschreibung(), wert.reihenfolge(), wert.aktiv());
        }
    }

    @Schema(name = "Klassifikation")
    public record KlassifikationDto(String code, String name, String beschreibung, List<WertDto> werte,
                                    boolean mehrfach, boolean kiVorschlag, boolean suchfilter,
                                    boolean oeffentlichFilter, boolean aktiv) {
        static KlassifikationDto aus(Klassifikation klassifikation) {
            return new KlassifikationDto(klassifikation.code(), klassifikation.name(), klassifikation.beschreibung(),
                    klassifikation.werte().stream().map(WertDto::aus).toList(), klassifikation.mehrfach(),
                    klassifikation.kiVorschlag(), klassifikation.suchfilter(), klassifikation.oeffentlichFilter(),
                    klassifikation.aktiv());
        }
    }

    @GET
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Klassifikationen des Mandanten auflisten")
    @APIResponse(responseCode = "200", description = "Aktive und inaktive Klassifikationen und Werte mit Status")
    public Response liste() {
        aufloesung.pruefen();
        return Response.ok(verwaltung.alle().stream().map(KlassifikationDto::aus).toList())
                .tag(new EntityTag(Integer.toString(verwaltung.aktuelleVersion()))).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Klassifikation anlegen", description = "If-Match mit Konfigurationsversion ist Pflicht.")
    @APIResponse(responseCode = "201", description = "Angelegt")
    @APIResponse(responseCode = "422", description = "KONFIGURATION_UNGUELTIG")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response anlegen(@HeaderParam(HttpHeaders.IF_MATCH) String ifMatch, String body) {
        aufloesung.pruefen();
        JsonNode json = objekt(body, ANLAGEFELDER);
        String code = erforderlichText(json, "code");
        String name = erforderlichText(json, "name");
        boolean mehrfach = erforderlichBool(json, "mehrfach");
        boolean kiVorschlag = erforderlichBool(json, "kiVorschlag");
        boolean suchfilter = erforderlichBool(json, "suchfilter");
        boolean oeffentlichFilter = erforderlichBool(json, "oeffentlichFilter");
        boolean aktiv = optionalBool(json, "aktiv", true);
        Klassifikation neu = new Klassifikation(code, name, text(json, "beschreibung"),
                werte(json.get("werte")), mehrfach, kiVorschlag, suchfilter, oeffentlichFilter, aktiv);
        Klassifikation gespeichert = verwaltung.anlegen(neu, version(ifMatch), wer(), grund(json));
        return Response.status(Response.Status.CREATED).entity(KlassifikationDto.aus(gespeichert))
                .tag(neueVersion()).build();
    }

    @PATCH
    @Path("/{code}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Klassifikation ändern oder deaktivieren",
            description = "Code bleibt unverändert. If-Match mit Konfigurationsversion ist Pflicht.")
    @APIResponse(responseCode = "200", description = "Geändert")
    @APIResponse(responseCode = "422", description = "KONFIGURATION_UNGUELTIG")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response aendern(@PathParam("code") String code,
                            @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch, String body) {
        aufloesung.pruefen();
        JsonNode json = objekt(body, AENDERUNGSFELDER);
        if (json.has("code")) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
        Set<String> gesetzt = gesetzteFelder(json, AENDERUNGSFELDER);
        gesetzt.remove("grund");
        if (gesetzt.isEmpty()) {
            throw ungueltig();
        }
        KlassifikationAenderung aenderung = new KlassifikationAenderung(gesetzt, text(json, "name"),
                text(json, "beschreibung"), bool(json, "mehrfach"), bool(json, "kiVorschlag"),
                bool(json, "suchfilter"), bool(json, "oeffentlichFilter"), bool(json, "aktiv"));
        Klassifikation gespeichert = verwaltung.aendern(code, aenderung, version(ifMatch), wer(), grund(json));
        return Response.ok(KlassifikationDto.aus(gespeichert)).tag(neueVersion()).build();
    }

    @DELETE
    @Path("/{code}")
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Klassifikation löschen", description = "Nur ohne Dokumente und Artzuordnungen.")
    @APIResponse(responseCode = "204", description = "Gelöscht")
    @APIResponse(responseCode = "409", description = "KONFIGURATION_IN_GEBRAUCH")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response loeschen(@PathParam("code") String code,
                             @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch) {
        aufloesung.pruefen();
        verwaltung.loeschen(code, version(ifMatch), wer(), null);
        return Response.noContent().tag(neueVersion()).build();
    }

    @POST
    @Path("/{code}/werte")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Wert anlegen")
    @APIResponse(responseCode = "201", description = "Angelegt")
    @APIResponse(responseCode = "422", description = "KONFIGURATION_UNGUELTIG")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response wertAnlegen(@PathParam("code") String code,
                                @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch, String body) {
        aufloesung.pruefen();
        JsonNode json = objekt(body, WERTFELDER);
        KlassifikationWert wert = wert(json);
        KlassifikationWert gespeichert = verwaltung.wertAnlegen(code, wert, version(ifMatch), wer(), grund(json));
        return Response.status(Response.Status.CREATED).entity(WertDto.aus(gespeichert)).tag(neueVersion()).build();
    }

    @PATCH
    @Path("/{code}/werte/{wert}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Wert ändern oder deaktivieren")
    @APIResponse(responseCode = "200", description = "Geändert")
    @APIResponse(responseCode = "422", description = "KONFIGURATION_UNGUELTIG")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response wertAendern(@PathParam("code") String code, @PathParam("wert") String wert,
                                @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch, String body) {
        aufloesung.pruefen();
        JsonNode json = objekt(body, WERTAENDERUNGSFELDER);
        if (json.has("code")) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
        Set<String> gesetzt = gesetzteFelder(json, WERTAENDERUNGSFELDER);
        gesetzt.remove("grund");
        if (gesetzt.isEmpty()) {
            throw ungueltig();
        }
        KlassifikationWertAenderung aenderung = new KlassifikationWertAenderung(gesetzt, text(json, "name"),
                text(json, "beschreibung"), integer(json, "reihenfolge"), bool(json, "aktiv"));
        KlassifikationWert gespeichert = verwaltung.wertAendern(code, wert, aenderung, version(ifMatch), wer(),
                grund(json));
        return Response.ok(WertDto.aus(gespeichert)).tag(neueVersion()).build();
    }

    @DELETE
    @Path("/{code}/werte/{wert}")
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Wert löschen", description = "Nur ohne Dokumente.")
    @APIResponse(responseCode = "204", description = "Gelöscht")
    @APIResponse(responseCode = "409", description = "KONFIGURATION_IN_GEBRAUCH")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response wertLoeschen(@PathParam("code") String code, @PathParam("wert") String wert,
                                 @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch) {
        aufloesung.pruefen();
        verwaltung.wertLoeschen(code, wert, version(ifMatch), wer(), null);
        return Response.noContent().tag(neueVersion()).build();
    }

    private EntityTag neueVersion() {
        return new EntityTag(Integer.toString(verwaltung.aktuelleVersion()));
    }

    private String wer() {
        return identitaet.getPrincipal().getName();
    }

    private static KlassifikationWert wert(JsonNode json) {
        return new KlassifikationWert(erforderlichText(json, "code"), erforderlichText(json, "name"),
                text(json, "beschreibung"), integer(json, "reihenfolge") == null ? 0 : integer(json, "reihenfolge"),
                optionalBool(json, "aktiv", true));
    }

    private static List<KlassifikationWert> werte(JsonNode array) {
        if (array == null) {
            return List.of();
        }
        if (!array.isArray()) {
            throw ungueltig();
        }
        List<KlassifikationWert> werte = new ArrayList<>();
        for (JsonNode element : array) {
            if (!element.isObject()) {
                throw ungueltig();
            }
            pruefeFelder(element, WERTFELDER);
            werte.add(wert(element));
        }
        return List.copyOf(werte);
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
        pruefeFelder(json, erlaubt);
        return json;
    }

    private static void pruefeFelder(JsonNode json, Set<String> erlaubt) {
        for (Iterator<String> namen = json.fieldNames(); namen.hasNext();) {
            if (!erlaubt.contains(namen.next())) {
                throw ungueltig();
            }
        }
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

    private static boolean erforderlichBool(JsonNode json, String feld) {
        Boolean wert = bool(json, feld);
        if (wert == null) {
            throw ungueltig();
        }
        return wert;
    }

    private static boolean optionalBool(JsonNode json, String feld, boolean standard) {
        Boolean wert = bool(json, feld);
        return wert == null ? standard : wert;
    }

    private static Integer integer(JsonNode json, String feld) {
        JsonNode knoten = json.get(feld);
        if (knoten == null) {
            return null;
        }
        if (!knoten.isInt()) {
            throw ungueltig();
        }
        return knoten.intValue();
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
