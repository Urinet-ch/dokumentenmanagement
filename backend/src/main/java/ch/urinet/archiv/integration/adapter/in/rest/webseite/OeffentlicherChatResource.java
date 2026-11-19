package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicheFragen;
import ch.urinet.archiv.ki.domain.oeffentlich.OeffentlicheAusgabe;
import ch.urinet.archiv.ki.domain.oeffentlich.OeffentlicheQuelle;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.WebseitenZugang;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Optional;
import java.util.UUID;

/**
 * Öffentlicher Chatbot der Webseiten-Schnittstelle: {@code POST /public/v1/chat} (Spec 03, Aufgabe 11.3, Req 6.1–6.5;
 * design.md «Webseiten-Schnittstelle: Lesen»). Anonym für den Besucher: Der Server der Webseite ruft mit dem
 * Lese-Schlüssel auf und meldet Stufe ({@code X-Archiv-Zugriff}) und Client-Hash ({@code X-Archiv-Client}); beides liest
 * der {@link LeseZugriffFilter} nach dem Anmeldefilter. Fehler vor dem Stream kommen als Problem Details (400, 403, 429),
 * danach nur noch als Ereignis {@code fehler} mit festem Text. Weder Frage noch Antwort erscheinen im Log.
 */
@Path("/public/v1/chat")
@Tag(name = "Webseite")
public class OeffentlicherChatResource {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String HINWEIS_STANDARD = "archiv.webseite.chat.hinweis";

    private final OeffentlicheFragen fragen;
    private final MandantAufloesung aufloesung;
    private final LeseAnfrage anfrage;
    private final Config konfiguration;

    @Inject
    public OeffentlicherChatResource(OeffentlicheFragen fragen, MandantAufloesung aufloesung, LeseAnfrage anfrage,
                                     Config konfiguration) {
        this.fragen = fragen;
        this.aufloesung = aufloesung;
        this.anfrage = anfrage;
        this.konfiguration = konfiguration;
    }

    @Schema(name = "ChatFrage")
    public record ChatFrageDto(
            @Schema(required = true, minLength = 1, maxLength = 500,
                    description = "Frage, nach Trim 1 bis 500 Zeichen (Obergrenze konfigurierbar, Standard 500)") String frage,
            @Schema(nullable = true, description = "Gespräch fortsetzen; ohne beginnt ein neues. Das Gespräch lebt "
                    + "höchstens 30 Minuten im Speicher des Archivs und gehört zum Client; eine unbekannte, fremde "
                    + "oder abgelaufene ID beginnt ein neues Gespräch") UUID gespraechId) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Blocking
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    @Operation(summary = "Frage an den öffentlichen Chatbot",
            description = "Antwort als Server-Sent Events mit benannten Ereignissen, JSON in data: «werkzeug» "
                    + "{werkzeug} je Werkzeugaufruf, «text» {delta}, genau ein «quellen» [{dokumentId, titel, reihe "
                    + "{code, name}, ausgabe, jahr, fundstelle {seite}}] vor «fertig» {gespraechId, hinweis}; "
                    + "«fehler» {meldung, gespraechId} mit festem "
                    + "Text beendet den Stream ohne «fertig». Der Chatbot antwortet nur aus veröffentlichten Dokumenten "
                    + "bis zur gemeldeten Stufe; ohne passende Quelle wird keine Modellantwort ausgegeben und «quellen» "
                    + "ist leer. Die Webseite zeigt «hinweis» unter dem Chatfenster und bildet Dokumentlinks aus "
                    + "dokumentId. Vor dem Stream: 400 ANFRAGE_UNGUELTIG, 403 NICHT_ERLAUBT, 429 ZU_VIELE_ANFRAGEN mit "
                    + "Retry-After und dem Feld grund (STUNDENLIMIT: mehr als 20 Fragen je Client oder je Webseite in "
                    + "60 Minuten; LAEUFT: schon eine Frage des Clients, Retry-After 5 s) oder 429 BUDGET (Monatsbudget "
                    + "des Mandanten erschöpft, ohne Retry-After; die Suche funktioniert weiterhin).")
    @Parameter(name = "X-Archiv-Zugriff", in = ParameterIn.HEADER, required = false,
            schema = @Schema(type = SchemaType.STRING, enumeration = {"oeffentlich", "mitglieder", "vorstand"}),
            description = "Stufe des Besuchers; fehlt der Header, gilt oeffentlich; über der Stufe des Schlüssels 403")
    @Parameter(name = "X-Archiv-Client", in = ParameterIn.HEADER, required = false,
            schema = @Schema(type = SchemaType.STRING),
            description = "SHA-256 (64 Hexziffern, Kleinbuchstaben) von IP und Tages-Salz, von der Webseite berechnet; "
                    + "ohne Header zählen alle Besucher ohne Hash der Webseite zusammen")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ChatFrageDto.class)))
    @APIResponse(responseCode = "200", description = "Stream der Ereignisse",
            content = @Content(mediaType = MediaType.SERVER_SENT_EVENTS, schema = @Schema(type = SchemaType.STRING)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public Multi<OutboundSseEvent> fragen(String body, @Context Sse sse) {
        MandantCode mandant = aufloesung.pruefen();
        Eingabe eingabe = eingabe(body);
        OeffentlicheFragen.Annahme annahme = fragen.annehmen(mandant, anfrage.zugriff(), eingabe.frage(),
                eingabe.gespraechId());
        String hinweis = hinweis(konfiguration, mandant);
        ChatAbbruch abbruch = new ChatAbbruch();
        // sofort starten und puffern, bis Quarkus abonniert: der Platz wird auch frei, wenn nie jemand abonniert
        UnicastProcessor<OutboundSseEvent> strom = UnicastProcessor.create();
        fragen.starten(annahme, ausgabe -> {
            strom.onNext(sse.newEventBuilder().name(ausgabe.name()).mediaType(MediaType.TEXT_PLAIN_TYPE)
                    .data(String.class, daten(ausgabe, hinweis)).build());
            if (ausgabe.letzte()) {
                strom.onComplete();
            }
        }, abbruch);
        // Client hat den Stream geschlossen (Abmeldung des Abonnenten)
        return strom.onCancellation().invoke(abbruch::setzen);
    }

    record Eingabe(String frage, Optional<UUID> gespraechId) {
    }

    /**
     * {@code {"frage": <Text>, "gespraechId"?: <UUID>}}; alles andere (kein Objekt, Frage kein Text, ID keine UUID) ergibt
     * 400 {@code ANFRAGE_UNGUELTIG} ohne den Wert. Unbekannte Felder werden ignoriert (additiver Vertrag). Die Länge prüft
     * {@link OeffentlicheFragen#frage}.
     */
    static Eingabe eingabe(String body) {
        try {
            if (body == null || body.isBlank()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            JsonNode wurzel = JSON.readTree(body);
            JsonNode frage = wurzel != null && wurzel.isObject() ? wurzel.get("frage") : null;
            if (frage == null || !frage.isTextual()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            JsonNode id = wurzel.get("gespraechId");
            if (id == null || id.isNull()) {
                return new Eingabe(frage.asText(), Optional.empty());
            }
            if (!id.isTextual()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            return new Eingabe(frage.asText(), Optional.of(UUID.fromString(id.asText())));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    /**
     * Hinweis unter dem Chatfenster ({@code fertig.hinweis}): {@code archiv.mandant.<code>.chat-hinweis}, sonst der
     * mandantenneutrale Standard {@value #HINWEIS_STANDARD}; einzeilig, ohne Steuerzeichen.
     */
    static String hinweis(Config konfiguration, MandantCode mandant) {
        Optional<String> eigener = mandant == null ? Optional.empty() : konfiguration.getOptionalValue(
                "archiv.mandant." + mandant.wert() + ".chat-hinweis", String.class).map(OeffentlicherChatResource::einzeilig);
        return eigener.filter(h -> !h.isEmpty()).orElseGet(() -> {
            String standard = einzeilig(konfiguration.getValue(HINWEIS_STANDARD, String.class));
            if (standard.isEmpty()) {
                throw new IllegalStateException(HINWEIS_STANDARD + " darf nicht leer sein");
            }
            return standard;
        });
    }

    private static String einzeilig(String wert) {
        return wert.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").strip();
    }

    /** JSON in {@code data:} eines Ereignisses; {@code hinweis} nur für {@code fertig}. */
    static String daten(OeffentlicheAusgabe ausgabe, String hinweis) {
        return switch (ausgabe) {
            case OeffentlicheAusgabe.Werkzeug w -> JSON.createObjectNode().put("werkzeug", w.werkzeug()).toString();
            case OeffentlicheAusgabe.Text t -> JSON.createObjectNode().put("delta", t.delta()).toString();
            case OeffentlicheAusgabe.Quellen q -> quellen(q).toString();
            case OeffentlicheAusgabe.Fertig f -> JSON.createObjectNode().put("gespraechId", f.gespraechId().toString())
                    .put("hinweis", hinweis).toString();
            case OeffentlicheAusgabe.Fehler f -> JSON.createObjectNode().put("meldung", f.meldung())
                    .put("gespraechId", f.gespraechId().toString()).toString();
        };
    }

    private static ArrayNode quellen(OeffentlicheAusgabe.Quellen q) {
        ArrayNode liste = JSON.createArrayNode();
        for (OeffentlicheQuelle quelle : q.quellen()) {
            ObjectNode knoten = liste.addObject();
            knoten.put("dokumentId", quelle.dokumentId().toString());
            knoten.put("titel", quelle.titel());
            knoten.putObject("reihe").put("code", quelle.reiheCode()).put("name", quelle.reiheName());
            if (quelle.ausgabe() == null) {
                knoten.putNull("ausgabe");
            } else {
                knoten.put("ausgabe", quelle.ausgabe());
            }
            if (quelle.jahr() == null) {
                knoten.putNull("jahr");
            } else {
                knoten.put("jahr", quelle.jahr());
            }
            if (quelle.seite() == null) {
                knoten.putNull("fundstelle");
            } else {
                knoten.putObject("fundstelle").put("seite", quelle.seite());
            }
        }
        return liste;
    }
}
