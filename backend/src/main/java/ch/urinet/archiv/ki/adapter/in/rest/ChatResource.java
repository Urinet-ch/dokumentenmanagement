package ch.urinet.archiv.ki.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.adapter.json.ChatJson;
import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.chat.ChatBewertungen;
import ch.urinet.archiv.ki.application.chat.ChatFragen;
import ch.urinet.archiv.ki.application.chat.ChatGespraecheAnlegen;
import ch.urinet.archiv.ki.application.chat.ChatVerlauf;
import ch.urinet.archiv.ki.domain.chat.ChatBewertung;
import ch.urinet.archiv.ki.domain.chat.ChatNachricht;
import ch.urinet.archiv.ki.domain.chat.Chatmodus;
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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    static final int BEWERTUNG_KOERPER_GRENZE = 8192;

    private final ChatFragen fragen;
    private final ChatGespraecheAnlegen anlegen;
    private final ChatVerlauf verlauf;
    private final ChatBewertungen bewertungen;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;
    private final Instance<HttpServerRequest> anfrage;

    public ChatResource(ChatFragen fragen, ChatGespraecheAnlegen anlegen, ChatVerlauf verlauf, ChatBewertungen bewertungen,
                        MandantAufloesung aufloesung, SecurityIdentity identitaet,
                        Instance<HttpServerRequest> anfrage) {
        this.fragen = fragen;
        this.anlegen = anlegen;
        this.verlauf = verlauf;
        this.bewertungen = bewertungen;
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

    @Schema(name = "ChatQuelle", description = "Dokument (id, titel, link), Datenstand eines Zahlenwerkzeugs "
            + "(werkzeug, datenstand, belege) oder Wissensseite mit direkten Quelldokumenten (wissen, dokumente)")
    public record QuelleDto(
            @Schema(nullable = true) UUID id,
            @Schema(nullable = true) String titel,
            @Schema(nullable = true, description = "relativer PWA-Pfad /dokument/<id>") String link,
            @Schema(nullable = true) String werkzeug,
            @Schema(nullable = true) Instant datenstand,
            @Schema(nullable = true) Integer belege,
            @Schema(nullable = true) WissensquelleDto wissen,
            @Schema(nullable = true) List<QuelldokumentDto> dokumente) {
    }

    @Schema(name = "ChatWissensquelle")
    public record WissensquelleDto(
            @Schema(nullable = true, description = "leer, solange Wissen nicht freigeschaltet ist (Spec 04, Req 22.7)")
            String titel,
            @Schema(nullable = true, description = "leer, solange Wissen nicht freigeschaltet ist (Spec 04, Req 22.7)")
            String link,
            Instant stand) {
    }

    @Schema(name = "ChatQuelldokument")
    public record QuelldokumentDto(String titel, String link, String seiten) {
    }

    @Schema(name = "ChatGespraech")
    public record GespraechDto(@Schema(required = true) UUID id, @Schema(required = true) String titel,
                               @Schema(required = true) Instant geaendert,
                               @Schema(required = true, enumeration = {"ARCHIV", "WISSEN"},
                                       description = "fester Modus des Gesprächs (Archivchat oder Wissenschat)")
                               String modus) {
    }

    @Schema(name = "ChatGespraechAnlegen")
    public record GespraechAnlegenDto(
            @Schema(nullable = true, enumeration = {"ARCHIV", "WISSEN"}, defaultValue = "ARCHIV",
                    description = "Modus des neuen Gesprächs; fehlt er, gilt ARCHIV. WISSEN setzt das Recht A15 voraus")
            String modus) {
    }

    @Schema(name = "ChatNachricht")
    public record NachrichtDto(
            @Schema(required = true, description = "Nachrichten-ID als Dezimalzahl") String id,
            @Schema(required = true, enumeration = {"BENUTZER", "ASSISTENT"}) String rolle,
            @Schema(required = true) String inhalt,
            @Schema(required = true) List<QuelleDto> quellen,
            @Schema(nullable = true, enumeration = {"OK", "FEHLER", "ABGEBROCHEN"},
                    description = "nur bei Antworten") String status,
            @Schema(required = true) Instant zeit,
            @Schema(required = true) boolean analyseMoeglich) {
    }

    @Schema(name = "ChatVerlauf")
    public record VerlaufDto(@Schema(required = true) UUID id, @Schema(required = true) String titel,
                             @Schema(required = true) Instant geaendert,
                             @Schema(required = true, enumeration = {"ARCHIV", "WISSEN"}) String modus,
                             @Schema(required = true) List<NachrichtDto> nachrichten) {
    }

    @Schema(name = "ChatBewertung")
    public record BewertungDto(@Schema(required = true) String nachrichtId,
                               @Schema(required = true, minimum = "1", maximum = "5") int sterne,
                               @Schema(nullable = true, enumeration = {"NICHT_BEANTWORTET", "FALSCH",
                                       "UNVOLLSTAENDIG", "QUELLE", "ZU_LANGSAM", "ANDERES"}) String grund,
                               @Schema(nullable = true, maxLength = ChatBewertung.MAX_FREITEXT) String freitext,
                               @Schema(required = true) Instant erstellt,
                               @Schema(required = true) Instant geaendert) {
    }

    @Schema(name = "ChatBewertungEingabe")
    public record BewertungEingabeDto(@Schema(required = true, minimum = "1", maximum = "5") Integer sterne,
                                      @Schema(nullable = true) String grund,
                                      @Schema(nullable = true, maxLength = ChatBewertung.MAX_FREITEXT) String freitext) {
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
                    + "datenstand, belege} | {wissen: {titel, link, stand}, dokumente: [{titel, link, seiten}]}] "
                    + "vor «fertig» {gespraechId, nachrichtId, tokens: {ein, aus}}; «fehler» {meldung, "
                    + "gespraechId} beendet den Stream ohne «fertig». Vor dem Stream: 400 ANFRAGE_UNGUELTIG, 404 "
                    + "NICHT_GEFUNDEN (fremdes oder unbekanntes Gespräch), 429 ZU_VIELE_ANFRAGEN mit Retry-After "
                    + "und dem Feld grund der Problem Details (STUNDENLIMIT: mehr als 100 Fragen in 60 Minuten; "
                    + "LAEUFT: schon eine laufende Frage, Retry-After höchstens 5 s). Nur beschreibend: der "
                    + "generierte Client liest den Stream nicht.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ChatFrageDto.class)))
    @APIResponse(responseCode = "200", description = "Stream der Ereignisse",
            content = @Content(mediaType = MediaType.SERVER_SENT_EVENTS, schema = @Schema(type = SchemaType.STRING)))
    @APIResponse(responseCode = "429", description = "Rate-Limit (grund STUNDENLIMIT) oder schon eine laufende Frage (grund LAEUFT)",
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

    @POST
    @Path("/gespraeche")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Gespräch mit Modus anlegen",
            description = "Legt ein eigenes Gespräch ohne Frage an (Spec 04, Requirement 21.1 und 21.5). Modus ARCHIV "
                    + "(Standard, auch ohne Körper) oder WISSEN (nur aus der Wissensbasis, Recht A15, sonst 403); ein "
                    + "anderer Wert ergibt 400 ANFRAGE_UNGUELTIG. Der Modus bleibt für das Gespräch fest. Die Fragen "
                    + "folgen wie bisher mit POST /chat und der gespraechId; der Titel ist bis zur ersten Frage "
                    + "«Neues Gespräch». Wer wiederholt anlegt, erhält das noch nicht gefragte Gespräch desselben "
                    + "Modus zurück (200 statt 201). Das Gespräch erscheint in der Liste mit seiner ersten Frage. Der "
                    + "Anfragekörper muss kleiner als 8 KiB sein.")
    @RequestBody(required = false, content = @Content(schema = @Schema(implementation = GespraechAnlegenDto.class)))
    @APIResponse(responseCode = "200", description = "Noch nicht gefragtes Gespräch desselben Modus zurückgegeben",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = GespraechDto.class)))
    @APIResponse(responseCode = "201", description = "Gespräch angelegt",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = GespraechDto.class)))
    @APIResponse(responseCode = "400", description = "Modus ungültig")
    @APIResponse(responseCode = "403", description = "Modus WISSEN ohne Recht A15")
    @APIResponse(responseCode = "413", description = "Anfragekörper zu gross")
    public Response gespraechAnlegen(String body) {
        aufloesung.pruefen();
        // die Grösse begrenzt schon die Route (ProblemFehlerRoute, auch chunked); hier nur noch die zweite Absicherung
        if (body != null && body.length() >= BEWERTUNG_KOERPER_GRENZE) {
            throw new FachFehler(Fehlercode.DATEI_ZU_GROSS);
        }
        Chatmodus modus = ChatGespraecheAnlegen.modus(gespraechModus(body));
        ChatGespraecheAnlegen.Ergebnis ergebnis = anlegen.anlegen(benutzer(), modus);
        return Response.status(ergebnis.neu() ? Response.Status.CREATED : Response.Status.OK)
                .entity(dto(ergebnis.kopf())).build();
    }

    @GET
    @Path("/gespraeche")
    @Produces(MediaType.APPLICATION_JSON)
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Eigene Gespräche", description = "Höchstens 20 eigene Gespräche mit Modus (ARCHIV oder "
            + "WISSEN) und mindestens einer Frage, zuletzt geänderte zuerst.")
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
        boolean wissen = verlauf.wissenVerfuegbar();
        return new VerlaufDto(kopf.id(), kopf.titel(), kopf.geaendert(), kopf.modus().name(),
                gespraech.nachrichten().stream().map(n -> dto(n, wissen)).toList());
    }

    @PUT
    @Path("/nachrichten/{id}/bewertung")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Eigene Chat-Antwort bewerten",
            description = "Erstellt oder ersetzt die Bewertung einer eigenen Antwort mit Status OK. Fremde, unbekannte "
                    + "und nicht bewertbare Antworten ergeben 404; nach mehr als 200 Speicherungen je Stunde 429. "
                    + "Der Anfragekörper muss kleiner als 8 KiB sein.")
    @RequestBody(required = true, description = "Bewertung der Antwort",
            content = @Content(schema = @Schema(implementation = BewertungEingabeDto.class)))
    @APIResponse(responseCode = "200", description = "Bewertung gespeichert")
    @APIResponse(responseCode = "400", description = "Bewertung ungültig")
    @APIResponse(responseCode = "404", description = "Antwort nicht gefunden")
    @APIResponse(responseCode = "413", description = "Bewertung zu gross")
    @APIResponse(responseCode = "429", description = "Bewertungslimit erreicht",
            headers = @Header(name = "Retry-After", description = "Sekunden bis zum nächsten Versuch"))
    public BewertungDto bewerten(@PathParam("id") String id, InputStream body) throws IOException {
        aufloesung.pruefen();
        BewertungEingabe eingabe = bewertungEingabe(bewertungKoerper(body));
        return dto(bewertungen.speichern(bewertungId(id), benutzer().id(), eingabe.sterne(),
                eingabe.grund(), eingabe.freitext()));
    }

    static String bewertungKoerper(InputStream body) throws IOException {
        byte[] eingabe = body.readNBytes(BEWERTUNG_KOERPER_GRENZE);
        if (eingabe.length == BEWERTUNG_KOERPER_GRENZE) {
            throw new FachFehler(Fehlercode.DATEI_ZU_GROSS);
        }
        return new String(eingabe, StandardCharsets.UTF_8);
    }

    @GET
    @Path("/nachrichten/{id}/bewertung")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Eigene Chat-Bewertung abrufen",
            description = "Fremde, unbekannte oder nicht bewertbare Antworten sowie Antworten ohne Bewertung ergeben 404.")
    @APIResponse(responseCode = "200", description = "Eigene Bewertung")
    @APIResponse(responseCode = "404", description = "Bewertung nicht gefunden")
    public BewertungDto bewertung(@PathParam("id") String id) {
        aufloesung.pruefen();
        return dto(bewertungen.lesen(bewertungId(id), benutzer().id()));
    }

    @DELETE
    @Path("/nachrichten/{id}/bewertung")
    @Blocking
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Eigene Chat-Bewertung entfernen",
            description = "Fremde, unbekannte und nicht bewertbare Antworten sowie Antworten ohne Bewertung ergeben 404.")
    @APIResponse(responseCode = "204", description = "Bewertung entfernt")
    @APIResponse(responseCode = "404", description = "Bewertung nicht gefunden")
    public Response bewertungLoeschen(@PathParam("id") String id) {
        aufloesung.pruefen();
        bewertungen.loeschen(bewertungId(id), benutzer().id());
        return Response.noContent().build();
    }

    record Eingabe(String frage, Optional<UUID> gespraechId) {
    }

    private record BewertungEingabe(int sterne, ChatBewertung.Grund grund, String freitext) {
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

    /** {@code {"modus"?: <Text>}}; leerer Körper und {@code null} sind kein Modus, alles andere als Text ist ungültig. */
    static String gespraechModus(String body) {
        try {
            if (body == null || body.isBlank()) {
                return null;
            }
            JsonNode wurzel = JSON.readTree(body);
            if (wurzel == null || !wurzel.isObject()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            JsonNode modus = wurzel.get("modus");
            if (modus == null || modus.isNull()) {
                return null;
            }
            if (!modus.isTextual()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            return modus.asText();
        } catch (JsonProcessingException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    private static BewertungEingabe bewertungEingabe(String body) {
        try {
            if (body == null || body.isBlank()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            JsonNode wurzel = JSON.readTree(body);
            JsonNode sterne = wurzel.isObject() ? wurzel.get("sterne") : null;
            JsonNode grund = wurzel.isObject() ? wurzel.get("grund") : null;
            JsonNode freitext = wurzel.isObject() ? wurzel.get("freitext") : null;
            if (sterne == null || !sterne.isIntegralNumber() || !sterne.canConvertToInt()
                    || grund != null && !grund.isNull() && !grund.isTextual()
                    || freitext != null && !freitext.isNull() && !freitext.isTextual()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            ChatBewertung.Grund grundWert = grund == null || grund.isNull() ? null
                    : ChatBewertung.Grund.valueOf(grund.asText());
            return new BewertungEingabe(sterne.asInt(), grundWert,
                    freitext == null || freitext.isNull() ? null : freitext.asText());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    private static long bewertungId(String id) {
        try {
            long wert = Long.parseLong(id);
            if (wert < 1) {
                throw new IllegalArgumentException("ungültige Nachrichten-ID");
            }
            return wert;
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
    }

    private static BewertungDto dto(ChatBewertung bewertung) {
        return new BewertungDto(Long.toString(bewertung.nachrichtId()), bewertung.sterne(),
                bewertung.grund() == null ? null : bewertung.grund().name(), bewertung.freitext(),
                bewertung.erstellt(), bewertung.geaendert());
    }

    private static GespraechDto dto(GespraechKopf kopf) {
        return new GespraechDto(kopf.id(), kopf.titel(), kopf.geaendert(), kopf.modus().name());
    }

    /** Ohne Freischaltung (Spec 04, Req 22.7): Wissensquellen ohne Titel und Link, keine Analyse. */
    private static NachrichtDto dto(ChatNachricht n, boolean wissen) {
        return new NachrichtDto(Long.toString(n.id()), n.rolle().name(), n.inhalt(),
                n.quellen().stream().map(q -> wissen ? dto(q) : ohneWissen(q)).toList(),
                n.status().map(Enum::name).orElse(null), n.zeit(), wissen && n.analyseMoeglich());
    }

    private static QuelleDto ohneWissen(Quelle quelle) {
        if (quelle instanceof Quelle.Wissen w) {
            return new QuelleDto(null, null, null, null, null, null, new WissensquelleDto(null, null, w.stand()),
                    w.dokumente().stream().map(d -> new QuelldokumentDto(d.titel(), d.link(), d.seite())).toList());
        }
        return dto(quelle);
    }

    private static QuelleDto dto(Quelle quelle) {
        return switch (quelle) {
            case Quelle.Dokument d -> new QuelleDto(d.id(), d.titel(), d.link(), null, null, null, null, null);
            case Quelle.Stand s -> new QuelleDto(null, null, null, s.werkzeug(), s.datenstand().zeitpunkt(),
                    s.datenstand().belege(), null, null);
            case Quelle.Wissen w -> new QuelleDto(null, null, null, null, null, null,
                    new WissensquelleDto(w.titel(), w.link(), w.stand()),
                    w.dokumente().stream().map(d -> new QuelldokumentDto(d.titel(), d.link(), d.seite())).toList());
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
