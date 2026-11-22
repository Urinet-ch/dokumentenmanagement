package ch.urinet.archiv.ki.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.application.chat.ChatAuswertungSpeicher;
import ch.urinet.archiv.ki.application.chat.ChatBewertungAuswertung;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
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
import java.util.Optional;

/** Inhaber-Auswertung und nicht persistierter ZIP-Download (Spec 02, Requirement 15.5–15.8). */
@Path("/chat/auswertung")
@Tag(name = "Chat")
public class ChatAuswertungResource {

    static final int BODY_MAX = 4_096;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatBewertungAuswertung auswertung;
    private final MandantAufloesung mandanten;
    private final SecurityIdentity identitaet;
    private final StepUp stepUp;
    private final Instance<HttpServerRequest> anfrage;

    public ChatAuswertungResource(ChatBewertungAuswertung auswertung, MandantAufloesung mandanten,
                                  SecurityIdentity identitaet, StepUp stepUp,
                                  Instance<HttpServerRequest> anfrage) {
        this.auswertung = auswertung;
        this.mandanten = mandanten;
        this.identitaet = identitaet;
        this.stepUp = stepUp;
        this.anfrage = anfrage;
    }

    @Schema(name = "ChatAuswertung")
    public record AuswertungDto(long anzahl, double durchschnitt, List<SterneDto> verteilung,
                                List<GrundDto> gruende, List<SchlechteAntwortDto> schlechteAntworten) {
    }

    public record SterneDto(int sterne, long anzahl) {
    }

    public record GrundDto(String grund, long anzahl) {
    }

    public record SchlechteAntwortDto(LocalDate datum, int sterne, String grund, String frage) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    @ErfordertAktion(Aktion.A24)
    @RolesAllowed("inhaber")
    @Operation(summary = "Chat-Bewertungen auswerten",
            description = "Kennzahlen für den Zeitraum sowie höchstens 100 Antworten mit höchstens 2 Sternen. "
                    + "Zeitraum maximal 12 Monate, nur Inhaber (A24).")
    @APIResponse(responseCode = "200", description = "Auswertung für den eigenen Mandanten",
            content = @Content(schema = @Schema(implementation = AuswertungDto.class)))
    public Response lesen(@QueryParam("von") String von, @QueryParam("bis") String bis) {
        mandanten.pruefen();
        ChatAuswertungSpeicher.Auswertung daten = auswertung.auswertung(datum(von), datum(bis));
        long[] werte = daten.sterne();
        List<SterneDto> verteilung = java.util.stream.IntStream.range(0, werte.length)
                .mapToObj(index -> new SterneDto(index + 1, werte[index])).toList();
        List<GrundDto> gruende = daten.gruende().stream().map(g -> new GrundDto(g.grund(), g.anzahl())).toList();
        List<SchlechteAntwortDto> schlecht = daten.schlechteAntworten().stream()
                .map(a -> new SchlechteAntwortDto(a.zeit().atZone(java.time.ZoneId.of("Europe/Zurich")).toLocalDate(),
                        a.sterne(), a.grund(), a.frage())).toList();
        return Response.ok(new AuswertungDto(daten.anzahl(), daten.durchschnitt(), verteilung, gruende, schlecht))
                .header("Cache-Control", "no-store").build();
    }

    @POST
    @Path("/export")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/zip")
    @Blocking
    @ErfordertAktion(Aktion.A24)
    @RolesAllowed("inhaber")
    @Protokolliert(aktion = ProtokollAktion.CHAT_BEWERTUNG_EXPORT, manuell = true)
    @Operation(summary = "Chat-Bewertungen exportieren",
            description = "ZIP im Format chat-bewertungen/v1; Step-up erforderlich; höchstens 5'000 Zeilen.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ExportEingabeDto.class)))
    @APIResponse(responseCode = "200", description = "ZIP-Download, nicht im Archiv abgelegt")
    @APIResponse(responseCode = "401", description = "Step-up erforderlich")
    @APIResponse(responseCode = "413", description = "Mehr als 5'000 Bewertungen im gewählten Zeitraum")
    public Response exportieren(InputStream body) {
        MandantCode mandant = mandanten.pruefen();
        stepUp.pruefen();
        ExportEingabe eingabe = eingabe(body);
        ChatBewertungAuswertung.Export export = auswertung.vorbereiten(eingabe.von(), eingabe.bis(),
                eingabe.minSterne(), eingabe.maxSterne(), eingabe.schwaerzen(), eingabe.klartextBestaetigt(),
                mandant, identitaet.getPrincipal().getName(),
                IpAdresse.aus(anfrage.get().getHeader("X-Real-Ip")));
        StreamingOutput zip = ziel -> {
            try (export) {
                export.schreiben(ziel);
            }
        };
        return Response.ok(zip).type("application/zip")
                .header("Content-Disposition", "attachment; filename=\"chat-bewertungen.zip\"")
                .header("Cache-Control", "no-store").build();
    }

    @Schema(name = "ChatBewertungExportEingabe")
    public record ExportEingabeDto(String von, String bis, Integer minSterne, Integer maxSterne,
                                   Boolean schwaerzen, Boolean klartextBestaetigt) {
    }

    private record ExportEingabe(LocalDate von, LocalDate bis, int minSterne, int maxSterne,
                                 boolean schwaerzen, boolean klartextBestaetigt) {
    }

    static ExportEingabe eingabe(InputStream quelle) {
        try {
            byte[] body = quelle.readNBytes(BODY_MAX + 1);
            if (body.length > BODY_MAX) {
                throw new FachFehler(Fehlercode.DATEI_ZU_GROSS);
            }
            JsonNode json = JSON.readTree(body);
            if (json == null || !json.isObject()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            LocalDate von = datum(json.path("von").asText(null));
            LocalDate bis = datum(json.path("bis").asText(null));
            int min = json.path("minSterne").isMissingNode() ? 1 : json.path("minSterne").asInt(-1);
            int max = json.path("maxSterne").isMissingNode() ? 5 : json.path("maxSterne").asInt(-1);
            boolean redigieren = !json.has("schwaerzen") || json.path("schwaerzen").asBoolean(false);
            boolean bestaetigt = json.path("klartextBestaetigt").asBoolean(false);
            if (min < 1 || max > 5 || min > max || !redigieren && !bestaetigt
                    || json.has("schwaerzen") && !json.path("schwaerzen").isBoolean()
                    || json.has("klartextBestaetigt") && !json.path("klartextBestaetigt").isBoolean()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            return new ExportEingabe(von, bis, min, max, redigieren, bestaetigt);
        } catch (IOException | IllegalArgumentException e) {
            if (e instanceof FachFehler fachFehler) {
                throw fachFehler;
            }
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    private static LocalDate datum(String wert) {
        try {
            return LocalDate.parse(wert);
        } catch (RuntimeException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }
}
