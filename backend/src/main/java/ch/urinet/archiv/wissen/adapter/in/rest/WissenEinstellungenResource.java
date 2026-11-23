package ch.urinet.archiv.wissen.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.adapter.StrengesJson;
import ch.urinet.archiv.gemeinsam.adapter.in.rest.ProblemAbbildung;
import ch.urinet.archiv.gemeinsam.adapter.in.rest.Versionskopf;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.konfiguration.application.BestaetigteEinstellungen;
import ch.urinet.archiv.konfiguration.application.WissenEinstellungenVerwaltung;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenEinstellungen;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
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
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Path("/wissensbasis/einstellungen")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Wissensbasis")
public class WissenEinstellungenResource {
    private static final int MAX_BYTES = 16 * 1024;
    private static final Set<String> FELDER = Set.of("budgetTokens", "lintBudgetTokens", "lintZeitplan",
            "monatsbudgetTokens");
    @Schema(name = "WissenEinstellungen")
    public record WerteDto(@Schema(nullable = true, minimum = "10000", maximum = "5000000") Integer budgetTokens,
                           @Schema(nullable = true, minimum = "10000", maximum = "1000000") Integer lintBudgetTokens,
                           @Schema(nullable = true) String lintZeitplan,
                           @Schema(nullable = true, minimum = "100000", maximum = "20000000") Integer monatsbudgetTokens) {
    }

    private final WissenEinstellungen einstellungen;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public WissenEinstellungenResource(WissenEinstellungen einstellungen, MandantAufloesung aufloesung,
                                       SecurityIdentity identitaet) {
        this.einstellungen = einstellungen;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @GET
    @ErfordertAktion(Aktion.A19)
    @RolesAllowed("inhaber")
    @Operation(summary = "Wissenseinstellungen lesen",
            description = "Nur Inhaber (A19); null bedeutet den konfigurierten Startwert.")
    @APIResponse(responseCode = "200", description = "Wissenseinstellungen",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = WerteDto.class)))
    public Response lesen() {
        aufloesung.pruefen();
        var stand = einstellungen.lesen(benutzer());
        return Response.ok(dto(stand.werte())).tag(tag(stand.version())).build();
    }

    @PUT
    @ErfordertAktion(Aktion.A19)
    @RolesAllowed("inhaber")
    @Operation(summary = "Wissenseinstellungen ersetzen",
            description = "Nur Inhaber (A19); null setzt den jeweiligen Startwert ein.")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = WerteDto.class)))
    @APIResponse(responseCode = "200", description = "Einstellungen gespeichert",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = WerteDto.class)))
    @APIResponse(responseCode = "422", description = "Budget oder Zeitplan ungültig")
    public Response speichern(@HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
                              @QueryParam("bestaetigt") String bestaetigt, InputStream body) throws IOException {
        aufloesung.pruefen();
        Anfrage anfrage = lesen(body);
        Benutzer benutzer = benutzer();
        var ergebnis = einstellungen.speichern(benutzer, Versionskopf.version(ifMatch), anfrage::eingabe,
                BestaetigteEinstellungen.lesen(bestaetigt));
        var stand = einstellungen.lesen(benutzer, ergebnis.version());
        return Response.ok(dto(stand.werte())).tag(tag(ergebnis.version())).build();
    }

    @ServerExceptionMapper
    public Response fachFehler(FachFehler fehler) {
        Response antwort = new ProblemAbbildung().fachFehler(fehler);
        return switch (fehler.code()) {
            case VERSION_FEHLT, VERSION_KONFLIKT, BESTAETIGUNG_NOETIG, STEP_UP_NOETIG,
                    KONFIGURATION_UNGUELTIG, ZU_VIELE_ANFRAGEN ->
                    Response.fromResponse(antwort).tag(tag(einstellungen.lesen(benutzer()).version())).build();
            default -> antwort;
        };
    }

    private static Anfrage lesen(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new FachFehler(Fehlercode.DATEI_ZU_GROSS);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return new Anfrage(StrengesJson.lesenGenau(text));
        } catch (CharacterCodingException | JsonProcessingException e) {
            return new Anfrage(null);
        }
    }

    private record Anfrage(JsonNode json) {
        WissenEinstellungenVerwaltung.Eingabe eingabe() {
            List<KonfigurationUngueltig.Befund> befunde = new ArrayList<>();
            if (json == null || !json.isObject()) {
                befunde.add(befund("wissen", "Die Anfrage muss ein gültiges JSON-Objekt enthalten."));
                return new WissenEinstellungenVerwaltung.Eingabe(
                        new WissenEinstellungen.Werte(null, null, null, null), befunde);
            }
            json.fieldNames().forEachRemaining(feld -> {
                if (!FELDER.contains(feld)) {
                    befunde.add(befund("wissen", "Die Anfrage enthält ein unbekanntes Feld."));
                }
            });
            Integer budget = zahl("budgetTokens", false, befunde);
            Integer lintBudget = zahl("lintBudgetTokens", false, befunde);
            Integer monat = zahl("monatsbudgetTokens", true, befunde);
            JsonNode zeitplan = json.get("lintZeitplan");
            String cron = null;
            if (zeitplan != null && !zeitplan.isNull()) {
                if (zeitplan.isTextual()) {
                    cron = zeitplan.textValue();
                } else {
                    befunde.add(befund("wissen.lintZeitplan", "Der Zeitplan muss ein Text sein."));
                }
            }
            return new WissenEinstellungenVerwaltung.Eingabe(
                    new WissenEinstellungen.Werte(budget, lintBudget, cron, monat), befunde);
        }

        private Integer zahl(String feld, boolean risiko, List<KonfigurationUngueltig.Befund> befunde) {
            JsonNode wert = json.get(feld);
            if (wert == null || wert.isNull()) {
                return null;
            }
            if (wert.isIntegralNumber() && wert.canConvertToInt()) {
                return wert.intValue();
            }
            befunde.add(befund("wissen." + feld, "Der Wert muss eine ganze Zahl sein."));
            if (!risiko || !wert.isNumber()) {
                return null;
            }
            BigDecimal zahl = wert.decimalValue().setScale(0, RoundingMode.CEILING);
            return zahl.max(BigDecimal.valueOf(Integer.MIN_VALUE)).min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
        }
    }

    private static KonfigurationUngueltig.Befund befund(String pfad, String meldung) {
        return new KonfigurationUngueltig.Befund("einstellungen." + pfad, "WERT_UNGUELTIG", meldung);
    }

    private static EntityTag tag(int version) {
        return new EntityTag(Integer.toString(version));
    }

    private static WerteDto dto(WissenEinstellungen.Werte werte) {
        return new WerteDto(werte.budgetTokens(), werte.lintBudgetTokens(), werte.lintZeitplan(),
                werte.monatsbudgetTokens());
    }

    private Benutzer benutzer() {
        return new Benutzer(identitaet.getPrincipal().getName(), Rolle.ausRealmRollen(identitaet.getRoles())
                .orElseThrow());
    }
}
