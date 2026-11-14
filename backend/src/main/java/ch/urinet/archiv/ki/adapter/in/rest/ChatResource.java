package ch.urinet.archiv.ki.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.adapter.json.ChatJson;
import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.chat.ChatFragen;
import ch.urinet.archiv.ki.application.chat.ChatVerlauf;
import ch.urinet.archiv.ki.domain.chat.ChatNachricht;
import ch.urinet.archiv.ki.domain.chat.Gespraech;
import ch.urinet.archiv.ki.domain.chat.GespraechKopf;
import ch.urinet.archiv.ki.domain.chat.Quelle;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Chat-Schnittstelle (Spec 02, Aufgabe 7.3, Req 3.8, 4.1, 5.4, 5.5; design.md «Vertrag Welle 7»). Den Chat nutzen alle
 * vier Rollen mit dem Recht wie A2 (Entscheid Fabian 14.11.2026, E3): Die Endpunkte sind darum für alle Archiv-Rollen
 * erklärt ({@link AlleArchivRollen}, Mandant und Rolle prüft der {@code ZugangFilter}), gelten als lesend und zeigen
 * jedem nur seine eigenen Gespräche. Fehler vor dem Stream kommen als Problem Details (400, 404, 429), danach nur noch
 * als Ereignis {@code fehler}. Weder Frage noch Antwort erscheinen im Log.
 */
@Path("/chat")
@Tag(name = "Chat")
public class ChatResource {

    private static final String REAL_IP = "X-Real-Ip";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatFragen fragen;
    private final ChatVerlauf verlauf;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;
    private final Instance<HttpServerRequest> anfrage;

    public ChatResource(ChatFragen fragen, ChatVerlauf verlauf, MandantAufloesung aufloesung,
                        SecurityIdentity identitaet, Instance<HttpServerRequest> anfrage) {
        this.fragen = fragen;
        this.verlauf = verlauf;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
        this.anfrage = anfrage;
    }

    @Schema(name = "ChatFrage")
    public record ChatFrageDto(
            @Schema(required = true, minLength = 1, maxLength = ChatFragen.MAX_FRAGE,
                    description = "Frage, nach Trim 1 bis 2'000 Zeichen") String frage,
            @Schema(nullable = true, description = "Gespräch fortsetzen; ohne beginnt ein neues") UUID gespraechId) {
    }

    @Schema(name = "ChatQuelle", description = "Dokument (id, titel, link) oder Datenstand eines Zahlenwerkzeugs "
            + "(werkzeug, datenstand, belege)")
    public record QuelleDto(
            @Schema(nullable = true) UUID id,
            @Schema(nullable = true) String titel,
            @Schema(nullable = true, description = "relativer PWA-Pfad /dokument/<id>") String link,
            @Schema(nullable = true) String werkzeug,
            @Schema(nullable = true) Instant datenstand,
            @Schema(nullable = true) Integer belege) {
    }

    @Schema(name = "ChatGespraech")
    public record GespraechDto(@Schema(required = true) UUID id, @Schema(required = true) String titel,
                               @Schema(required = true) Instant geaendert) {
    }

    @Schema(name = "ChatNachricht")
    public record NachrichtDto(
            @Schema(required = true, enumeration = {"BENUTZER", "ASSISTENT"}) String rolle,
            @Schema(required = true) String inhalt,
            @Schema(required = true) List<QuelleDto> quellen,
            @Schema(nullable = true, enumeration = {"OK", "FEHLER", "ABGEBROCHEN"},
                    description = "nur bei Antworten") String status,
            @Schema(required = true) Instant zeit) {
    }

    @Schema(name = "ChatVerlauf")
    public record VerlaufDto(@Schema(required = true) UUID id, @Schema(required = true) String titel,
                             @Schema(required = true) Instant geaendert,
                             @Schema(required = true) List<NachrichtDto> nachrichten) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Blocking
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Frage an den Chat",
            description = "Antwort als Server-Sent Events mit benannten Ereignissen, JSON in data: «werkzeug» {name} "
                    + "je Werkzeugaufruf, «text» {delta}, genau ein «quellen» [{id, titel, link} | {werkzeug, "
                    + "datenstand, belege}] vor «fertig» {gespraechId, tokens: {ein, aus}}; «fehler» {meldung, "
                    + "gespraechId} beendet den Stream ohne «fertig». Vor dem Stream: 400 ANFRAGE_UNGUELTIG, 404 "
                    + "NICHT_GEFUNDEN (fremdes oder unbekanntes Gespräch), 429 ZU_VIELE_ANFRAGEN mit Retry-After "
                    + "(mehr als 100 Fragen in 60 Minuten oder schon eine laufende Frage). Nur beschreibend: der "
                    + "generierte Client liest den Stream nicht.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ChatFrageDto.class)))
    @APIResponse(responseCode = "200", description = "Stream der Ereignisse",
            content = @Content(mediaType = MediaType.SERVER_SENT_EVENTS, schema = @Schema(type = SchemaType.STRING)))
    @APIResponse(responseCode = "429", description = "Rate-Limit oder schon eine laufende Frage",
            headers = @Header(name = "Retry-After", description = "Sekunden bis zum nächsten Versuch"))
    public Multi<OutboundSseEvent> fragen(String body, @Context Sse sse) {
        MandantCode mandant = aufloesung.pruefen();
        Eingabe eingabe = eingabe(body);
        ChatFragen.Annahme annahme = fragen.annehmen(benutzer(), mandant, ip(), eingabe.frage(), eingabe.gespraechId());
        ChatAbbruch abbruch = new ChatAbbruch();
        // sofort starten und puffern, bis Quarkus abonniert: der Platz wird auch frei, wenn nie jemand abonniert
        UnicastProcessor<OutboundSseEvent> strom = UnicastProcessor.create();
        fragen.starten(annahme, ausgabe -> {
            strom.onNext(sse.newEventBuilder().name(ausgabe.name()).mediaType(MediaType.TEXT_PLAIN_TYPE)
                    .data(String.class, ChatJson.daten(ausgabe)).build());
            if (ausgabe.letzte()) {
                strom.onComplete();
            }
        }, abbruch);
        // Client hat den Stream geschlossen (Abmeldung des Abonnenten)
        return strom.onCancellation().invoke(abbruch::setzen);
    }

    @GET
    @Path("/gespraeche")
    @Produces(MediaType.APPLICATION_JSON)
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Eigene Gespräche", description = "Höchstens 20 eigene Gespräche, zuletzt geänderte zuerst.")
    public List<GespraechDto> gespraeche() {
        aufloesung.pruefen();
        return verlauf.gespraeche(benutzer()).stream().map(ChatResource::dto).toList();
    }

    @GET
    @Path("/gespraeche/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Verlauf eines eigenen Gesprächs",
            description = "Nachrichten mit Rolle, Inhalt, Quellen, Status und Zeit; fremd oder unbekannt → 404 "
                    + "NICHT_GEFUNDEN.")
    public VerlaufDto gespraech(@PathParam("id") String id) {
        aufloesung.pruefen();
        UUID gespraechId;
        try {
            gespraechId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        Gespraech gespraech = verlauf.gespraech(benutzer(), gespraechId);
        GespraechKopf kopf = gespraech.kopf();
        return new VerlaufDto(kopf.id(), kopf.titel(), kopf.geaendert(),
                gespraech.nachrichten().stream().map(ChatResource::dto).toList());
    }

    record Eingabe(String frage, Optional<UUID> gespraechId) {
    }

    /**
     * {@code {"frage": <Text>, "gespraechId"?: <UUID>}}; alles andere (kein Objekt, Frage kein Text, ID keine UUID)
     * ergibt 400 {@code ANFRAGE_UNGUELTIG} ohne den Wert. Die Länge prüft {@link ChatFragen#frage}.
     */
    static Eingabe eingabe(String body) {
        try {
            if (body == null || body.isBlank()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            JsonNode wurzel = JSON.readTree(body);
            JsonNode frage = wurzel.isObject() ? wurzel.get("frage") : null;
            if (frage == null || !frage.isTextual()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            JsonNode id = wurzel.get("gespraechId");
            if (id == null || id.isNull()) {
                return new Eingabe(ChatFragen.frage(frage.asText()), Optional.empty());
            }
            if (!id.isTextual()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            return new Eingabe(ChatFragen.frage(frage.asText()), Optional.of(UUID.fromString(id.asText())));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    private static GespraechDto dto(GespraechKopf kopf) {
        return new GespraechDto(kopf.id(), kopf.titel(), kopf.geaendert());
    }

    private static NachrichtDto dto(ChatNachricht n) {
        return new NachrichtDto(n.rolle().name(), n.inhalt(), n.quellen().stream().map(ChatResource::dto).toList(),
                n.status().map(Enum::name).orElse(null), n.zeit());
    }

    private static QuelleDto dto(Quelle quelle) {
        return switch (quelle) {
            case Quelle.Dokument d -> new QuelleDto(d.id(), d.titel(), d.link(), null, null, null);
            case Quelle.Stand s -> new QuelleDto(null, null, null, s.werkzeug(), s.datenstand().zeitpunkt(),
                    s.datenstand().belege());
        };
    }

    private Optional<IpAdresse> ip() {
        try {
            return anfrage.isResolvable() ? IpAdresse.aus(anfrage.get().getHeader(REAL_IP)) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Der ZugangFilter hat Rolle und Benutzer-ID schon geprüft (sonst 403). */
    private Benutzer benutzer() {
        return new Benutzer(identitaet.getPrincipal().getName(),
                Rolle.ausRealmRollen(identitaet.getRoles()).orElseThrow());
    }
}
