package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.adapter.StrengesJson;
import ch.urinet.archiv.gemeinsam.adapter.in.rest.Versionskopf;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Befund;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Path("/konfiguration")
@Tag(name = "Konfiguration")
@Produces(MediaType.APPLICATION_JSON)
public class KonfigurationsdateiResource {

    private static final int BODY_MAX = 1024 * 1024;
    private static final int GRUND_MAX = 1000;
    private static final String GRUND_DATEI = "Konfigurationsdatei eingespielt";
    private final KonfigurationsdateiVerwaltung verwaltung;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public KonfigurationsdateiResource(KonfigurationsdateiVerwaltung verwaltung, MandantAufloesung aufloesung,
                                       SecurityIdentity identitaet) {
        this.verwaltung = verwaltung;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @Schema(name = "KonfigurationsdateiAnalyse")
    public record AnalyseDto(int aktuelleVersion, Integer basisVersion, boolean gueltig, List<Befund> befunde,
                             List<KonfigurationsdateiVerwaltung.DiffEintrag> aenderungen) {
        static AnalyseDto aus(KonfigurationsdateiVerwaltung.Analyse analyse) {
            return new AnalyseDto(analyse.aktuelleVersion(), analyse.basisVersion(), analyse.gueltig(),
                    analyse.befunde(), analyse.aenderungen());
        }
    }

    @Schema(name = "KonfigurationsdateiErgebnis")
    public record ErgebnisDto(int version, boolean geaendert,
                              List<KonfigurationsdateiVerwaltung.DiffEintrag> aenderungen) {
        static ErgebnisDto aus(KonfigurationsdateiVerwaltung.Ergebnis ergebnis) {
            return new ErgebnisDto(ergebnis.version(), !ergebnis.aenderungen().isEmpty(), ergebnis.aenderungen());
        }
    }

    @Schema(name = "KonfigurationKiTexte")
    public record KiTexteDto(String rolle, String feldregeln, boolean kiFreigegeben) {
        static KiTexteDto aus(ch.urinet.archiv.konfiguration.domain.Konfiguration.KiTexte texte) {
            return new KiTexteDto(texte.rolle(), texte.feldregeln(), texte.kiFreigegeben());
        }
    }

    @GET
    @RolesAllowed("inhaber")
    @ErfordertAktion(Aktion.A11)
    @Operation(summary = "Konfiguration als Datei exportieren")
    @APIResponse(responseCode = "200", description = "Aktuelle Konfiguration als JSON-Datei")
    @APIResponse(responseCode = "403", description = "NICHT_ERLAUBT")
    public Response exportieren() {
        aufloesung.pruefen();
        KonfigurationsdateiVerwaltung.Export export = verwaltung.exportieren();
        return Response.ok(export.datei(), MediaType.APPLICATION_JSON_TYPE)
                .tag(new EntityTag(Integer.toString(export.version()))).build();
    }

    @POST
    @Path("/pruefen")
    @RolesAllowed("inhaber")
    @ErfordertAktion(Aktion.A11)
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Konfigurationsdatei prüfen", description = "Prüft die Datei, ohne Änderungen vorzunehmen.")
    @APIResponse(responseCode = "200", description = "Befunde und Vergleich")
    @APIResponse(responseCode = "413", description = "DATEI_ZU_GROSS")
    @APIResponse(responseCode = "422", description = "KONFIGURATION_UNGUELTIG")
    public AnalyseDto pruefen(String body) {
        aufloesung.pruefen();
        return AnalyseDto.aus(verwaltung.pruefen(body));
    }

    @POST
    @Path("/vergleichen")
    @RolesAllowed("inhaber")
    @ErfordertAktion(Aktion.A11)
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Konfigurationsdatei vergleichen", description = "Vergleicht die Datei mit dem aktuellen Stand.")
    @APIResponse(responseCode = "200", description = "Änderungen und betroffene Dokumente")
    @APIResponse(responseCode = "413", description = "DATEI_ZU_GROSS")
    @APIResponse(responseCode = "422", description = "KONFIGURATION_UNGUELTIG")
    public AnalyseDto vergleichen(String body) {
        aufloesung.pruefen();
        return AnalyseDto.aus(verwaltung.vergleichen(body));
    }

    @PUT
    @RolesAllowed("inhaber")
    @ErfordertAktion(Aktion.A11)
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Konfigurationsdatei einspielen",
            description = "Massgeblich ist die basisVersion in der Datei; weicht sie vom aktuellen Stand ab, "
                    + "kommt 409. Ein If-Match wird nicht ausgewertet.")
    @APIResponse(responseCode = "200", description = "Eingespielt oder bereits aktuell")
    @APIResponse(responseCode = "409", description = "KONFIGURATION_VERALTET")
    @APIResponse(responseCode = "413", description = "DATEI_ZU_GROSS")
    @APIResponse(responseCode = "422", description = "KONFIGURATION_UNGUELTIG")
    public Response einspielen(@HeaderParam("X-Aenderungsgrund") String aenderungsgrund, String body) {
        aufloesung.pruefen();
        String grund = aenderungsgrund == null || aenderungsgrund.isBlank() ? GRUND_DATEI : aenderungsgrund.strip();
        if (grund.codePointCount(0, grund.length()) > GRUND_MAX) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        KonfigurationsdateiVerwaltung.Ergebnis gespeichert =
                verwaltung.einspielen(body, wer(), grund);
        return Response.ok(ErgebnisDto.aus(gespeichert))
                .tag(new EntityTag(Integer.toString(gespeichert.version()))).build();
    }

    @GET
    @Path("/ki-texte")
    @RolesAllowed("inhaber")
    @ErfordertAktion(Aktion.A11)
    @Operation(summary = "KI-Texte des Mandanten ansehen")
    @APIResponse(responseCode = "200", description = "Rolle, Feldregeln und Freigabestatus")
    public Response kiTexte() {
        aufloesung.pruefen();
        return Response.ok(KiTexteDto.aus(verwaltung.kiTexte()))
                .tag(new EntityTag(Integer.toString(verwaltung.aktuelleVersion()))).build();
    }

    @PATCH
    @Path("/ki-texte")
    @RolesAllowed("inhaber")
    @ErfordertAktion(Aktion.A11)
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "KI-Texte ändern", description = "kiFreigegeben kann nur der Betreiber setzen.")
    @APIResponse(responseCode = "200", description = "Geändert")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    @APIResponse(responseCode = "422", description = "KONFIGURATION_UNGUELTIG")
    public Response kiTexteAendern(@HeaderParam(HttpHeaders.IF_MATCH) String ifMatch, String body) {
        aufloesung.pruefen();
        JsonNode json = objekt(body, Set.of("rolle", "feldregeln", "grund"));
        String rolle = text(json, "rolle");
        String feldregeln = text(json, "feldregeln");
        String grund = text(json, "grund");
        int version = verwaltung.kiTexteAendern(rolle, feldregeln, Versionskopf.version(ifMatch), wer(), grund);
        return Response.ok(KiTexteDto.aus(verwaltung.kiTexte()))
                .tag(new EntityTag(Integer.toString(version))).build();
    }

    private String wer() {
        return identitaet.getPrincipal().getName();
    }

    private static JsonNode objekt(String text, Set<String> felder) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > BODY_MAX) {
            throw new FachFehler(text == null ? Fehlercode.ANFRAGE_UNGUELTIG : Fehlercode.DATEI_ZU_GROSS);
        }
        try {
            JsonNode json = StrengesJson.lesenGenau(text);
            if (!(json instanceof ObjectNode)) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            List<String> unbekannt = new ArrayList<>();
            Iterator<String> namen = json.fieldNames();
            while (namen.hasNext()) {
                String name = namen.next();
                if (!felder.contains(name)) {
                    unbekannt.add(name);
                }
            }
            if (!unbekannt.isEmpty()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    private static String text(JsonNode json, String feld) {
        JsonNode wert = json.get(feld);
        if (wert == null || !wert.isTextual()) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return wert.textValue();
    }
}
