package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.adapter.in.rest.Versionskopf;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.gemeinsam.domain.BestaetigungNoetig.Auswirkung;
import ch.urinet.archiv.konfiguration.adapter.EinstellungenPatch;
import ch.urinet.archiv.konfiguration.application.BestaetigteEinstellungen;
import ch.urinet.archiv.konfiguration.application.EinstellungenVerwaltung;
import ch.urinet.archiv.konfiguration.application.EinstellungskatalogLesen;
import ch.urinet.archiv.konfiguration.application.KonfigurationVerwaltung;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Einstellungen;
import ch.urinet.archiv.konfiguration.domain.Einstellungskatalog;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

@Path("/mandant/einstellungen")
@Tag(name = "Einstellungen")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("inhaber")
public class MandantEinstellungenResource {
    @Schema(name = "EinstellungenZuruecksetzen")
    public record ZuruecksetzenDto(String gruppe, Integer version) {
    }

    @Schema(name = "EinstellungskatalogEintrag")
    public record KatalogEintragDto(String schluessel, Einstellungskatalog.Gruppe gruppe, String name,
                                    String beschreibung, Einstellungskatalog.Typ typ, String einheit,
                                    Integer minimum, Integer maximum, String formregel, Object standard,
                                    Object aktuellerWert, boolean eigenerWert, boolean riskant,
                                    String wirksamAb, String hinweis) {
    }

    @Schema(name = "OeffentlichesChatBudget")
    public record ChatBudgetDto(LocalDate monat, long budgetTokens, long verbrauchteTokens) {
    }

    @Schema(name = "EinstellungenBetrieb")
    public record BetriebDto(ChatBudgetDto oeffentlicherChat, long wissenMonatsbudgetTokens) {
    }

    @Schema(name = "EinstellungskatalogAntwort")
    public record KatalogAntwortDto(int version, List<KatalogEintragDto> katalog, BetriebDto betrieb) {
    }

    @Schema(name = "EinstellungenVorschau")
    public record VorschauDto(int version, List<Auswirkung> auswirkungen) {
    }

    @Schema(name = "EinstellungenErgebnis")
    public record ErgebnisDto(int version, boolean geaendert, List<Auswirkung> auswirkungen) {
    }

    private final EinstellungenVerwaltung verwaltung;
    private final EinstellungskatalogLesen katalog;
    private final KonfigurationVerwaltung konfiguration;
    private final MandantAufloesung aufloesung;
    private final Berechtigung rechte;
    private final SecurityIdentity identitaet;
    private final ObjectMapper json;

    public MandantEinstellungenResource(EinstellungenVerwaltung verwaltung, EinstellungskatalogLesen katalog,
                                        KonfigurationVerwaltung konfiguration,
                                        MandantAufloesung aufloesung, Berechtigung rechte,
                                        SecurityIdentity identitaet, ObjectMapper json) {
        this.verwaltung = verwaltung;
        this.katalog = katalog;
        this.konfiguration = konfiguration;
        this.aufloesung = aufloesung;
        this.rechte = rechte;
        this.identitaet = identitaet;
        this.json = json;
    }

    @GET
    @ErfordertAktion(Aktion.A20)
    @Operation(summary = "Einstellungskatalog und Betriebsbudget lesen")
    @APIResponse(responseCode = "200", description = "Katalog und aktueller Budgetstand",
            content = @Content(schema = @Schema(implementation = KatalogAntwortDto.class)))
    public Response lesen() {
        var ergebnis = katalog.lesen(benutzer().rolle());
        return Response.ok(ergebnis).tag(tag(ergebnis.version())).build();
    }

    @PATCH
    @ErfordertAktion(Aktion.A20)
    @Consumes({"application/merge-patch+json", MediaType.APPLICATION_JSON})
    @Operation(summary = "Einstellungen ändern")
    @RequestBody(required = true, content = {
            @Content(mediaType = "application/merge-patch+json", schema = @Schema(implementation = Einstellungen.class)),
            @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Einstellungen.class))})
    @APIResponse(responseCode = "200", description = "Änderung oder unveränderter Stand",
            content = @Content(schema = @Schema(implementation = ErgebnisDto.class)))
    @APIResponse(responseCode = "409", description = "BESTAETIGUNG_NOETIG")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "413", description = "DATEI_ZU_GROSS")
    @APIResponse(responseCode = "422", description = "KONFIGURATION_UNGUELTIG")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response aendern(@HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
                            @QueryParam("bestaetigt") String bestaetigt, InputStream body) throws IOException {
        aufloesung.pruefen();
        Anfrage anfrage = lesen(body);
        wissenPruefen(anfrage);
        var ergebnis = verwaltung.aendern(Versionskopf.version(ifMatch),
                vorher -> patch(vorher, anfrage.wert()), BestaetigteEinstellungen.lesen(bestaetigt), wer());
        return Response.ok(ergebnis).tag(tag(ergebnis.version())).build();
    }

    @POST
    @Path("/vorschau")
    @ErfordertAktion(Aktion.A20)
    @Consumes({"application/merge-patch+json", MediaType.APPLICATION_JSON})
    @Operation(summary = "Änderung der Einstellungen vorschauen")
    @RequestBody(required = true, content = {
            @Content(mediaType = "application/merge-patch+json", schema = @Schema(implementation = Einstellungen.class)),
            @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Einstellungen.class))})
    @APIResponse(responseCode = "200", description = "Auswirkungen ohne Änderung",
            content = @Content(schema = @Schema(implementation = VorschauDto.class)))
    public Response vorschau(@HeaderParam(HttpHeaders.IF_MATCH) String ifMatch, InputStream body) throws IOException {
        aufloesung.pruefen();
        Anfrage anfrage = lesen(body);
        wissenPruefen(anfrage);
        var ergebnis = verwaltung.vorschau(Versionskopf.version(ifMatch), vorher -> patch(vorher, anfrage.wert()));
        return Response.ok(ergebnis).tag(tag(ergebnis.version())).build();
    }

    @POST
    @Path("/zuruecksetzen")
    @ErfordertAktion(Aktion.A20)
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Einstellungen einer Gruppe oder Version zurücksetzen")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = ZuruecksetzenDto.class)))
    @APIResponse(responseCode = "200", description = "Zurückgesetzt oder unveränderter Stand",
            content = @Content(schema = @Schema(implementation = ErgebnisDto.class)))
    public Response zuruecksetzen(@HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
                                  @QueryParam("bestaetigt") String bestaetigt, InputStream body) throws IOException {
        aufloesung.pruefen();
        Anfrage anfrage = lesen(body);
        if (anfrage.json() != null && (anfrage.json().has("version")
                || "WISSEN".equals(anfrage.json().path("gruppe").asText()))) {
            rechte.aktion(benutzer(), Aktion.A19);
        }
        Function<Einstellungen, EinstellungenVerwaltung.Eingabe> eingabe = vorher -> {
            var auftrag = EinstellungenZuruecksetzen.lesen(anfrage.wert());
            if (auftrag.version() != null) {
                return new EinstellungenVerwaltung.Eingabe(
                        konfiguration.version(auftrag.version()).inhalt().einstellungen(), List.of());
            }
            return patch(vorher, EinstellungenZuruecksetzen.gruppenPatch(json, auftrag.gruppe()));
        };
        var ergebnis = verwaltung.aendern(Versionskopf.version(ifMatch), eingabe,
                BestaetigteEinstellungen.lesen(bestaetigt), wer());
        return Response.ok(ergebnis).tag(tag(ergebnis.version())).build();
    }

    private EinstellungenVerwaltung.Eingabe patch(Einstellungen vorher, JsonNode eingabe) {
        var ergebnis = EinstellungenPatch.anwenden(json, vorher, eingabe);
        return new EinstellungenVerwaltung.Eingabe(ergebnis.ziel(), ergebnis.befunde());
    }

    private void wissenPruefen(Anfrage anfrage) {
        if (anfrage.json() != null && anfrage.json().has("wissen")) {
            rechte.aktion(benutzer(), Aktion.A19);
        }
    }

    private Benutzer benutzer() {
        return new Benutzer(wer(), Rolle.ausRealmRollen(identitaet.getRoles()).orElseThrow());
    }

    private String wer() {
        return identitaet.getPrincipal().getName();
    }

    private static EntityTag tag(int version) {
        return new EntityTag(Integer.toString(version));
    }

    private static Anfrage lesen(InputStream body) throws IOException {
        try {
            return new Anfrage(EinstellungenPatch.lesen(body), null);
        } catch (KonfigurationUngueltig fehler) {
            return new Anfrage(null, fehler);
        }
    }

    private record Anfrage(JsonNode json, KonfigurationUngueltig fehler) {
        JsonNode wert() {
            if (fehler != null) {
                throw fehler;
            }
            return json;
        }
    }
}
