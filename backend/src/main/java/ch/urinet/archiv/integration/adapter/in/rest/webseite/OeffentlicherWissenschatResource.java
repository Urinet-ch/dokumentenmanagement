package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicheFragen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.WebseitenZugang;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
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

@Path("/public/v1/wissen/chat")
@Tag(name = "Webseite")
public class OeffentlicherWissenschatResource {
    @Schema(name = "WissenschatFrage")
    public record FrageDto(@Schema(required = true, minLength = 1, maxLength = 500) String frage) { }

    private final OeffentlicheFragen fragen;
    private final MandantAufloesung aufloesung;
    private final LeseAnfrage anfrage;
    private final Config konfiguration;

    public OeffentlicherWissenschatResource(OeffentlicheFragen fragen, MandantAufloesung aufloesung,
                                            LeseAnfrage anfrage, Config konfiguration) {
        this.fragen = fragen;
        this.aufloesung = aufloesung;
        this.anfrage = anfrage;
        this.konfiguration = konfiguration;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Blocking
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    @Operation(summary = "Frage an den öffentlichen Wissenschat",
            description = "Nur für den Mandanten aktivierte, ausdrücklich freigegebene Wissensseiten. "
                    + "Jede Frage ist unabhängig; Gesprächsverläufe werden nicht gespeichert.")
    @Parameter(name = "X-Archiv-Zugriff", in = ParameterIn.HEADER, required = false,
            schema = @Schema(type = SchemaType.STRING, enumeration = {"oeffentlich", "mitglieder", "vorstand"}))
    @Parameter(name = "X-Archiv-Client", in = ParameterIn.HEADER, required = false,
            schema = @Schema(type = SchemaType.STRING))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = FrageDto.class)))
    @APIResponse(responseCode = "200", description = "Stream der Ereignisse",
            content = @Content(mediaType = MediaType.SERVER_SENT_EVENTS, schema = @Schema(type = SchemaType.STRING)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public Multi<OutboundSseEvent> fragen(String body, @Context Sse sse) {
        var mandant = aufloesung.pruefen();
        if (!konfiguration.getOptionalValue("archiv.mandant." + mandant.wert() + ".oeffentlich.wissenschat",
                Boolean.class).orElse(false)) {
            throw new FachFehler(Fehlercode.NICHT_ERLAUBT);
        }
        var eingabe = OeffentlicherChatResource.eingabe(body);
        if (eingabe.gespraechId().isPresent()) throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        var annahme = fragen.wissenAnnehmen(mandant, anfrage.zugriff(), eingabe.frage());
        String hinweis = OeffentlicherChatResource.hinweis(konfiguration, mandant);
        ChatAbbruch abbruch = new ChatAbbruch();
        UnicastProcessor<OutboundSseEvent> strom = UnicastProcessor.create();
        fragen.starten(annahme, ausgabe ->
                strom.onNext(sse.newEventBuilder().name(ausgabe.name()).mediaType(MediaType.TEXT_PLAIN_TYPE)
                        .data(String.class, OeffentlicherChatResource.daten(ausgabe, hinweis)).build()),
                abbruch, strom::onComplete);
        return strom.onCancellation().invoke(abbruch::setzen);
    }
}
