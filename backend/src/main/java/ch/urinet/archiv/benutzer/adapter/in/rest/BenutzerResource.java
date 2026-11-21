package ch.urinet.archiv.benutzer.adapter.in.rest;

import ch.urinet.archiv.benutzer.application.BenutzerLesen;
import ch.urinet.archiv.benutzer.application.BenutzerEinladen;
import ch.urinet.archiv.benutzer.application.BenutzerAnlegen;
import ch.urinet.archiv.benutzer.application.BenutzerZugangZuruecksetzen;
import ch.urinet.archiv.benutzer.application.BenutzerGoogleLoesen;
import ch.urinet.archiv.benutzer.application.BenutzerEmailAendern;
import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Path("/benutzer")
@Tag(name = "Benutzerverwaltung")
@Produces(MediaType.APPLICATION_JSON)
public class BenutzerResource {
    private final BenutzerLesen lesen;
    private final BenutzerEinladen einladen;
    private final BenutzerAnlegen anlegen;
    private final BenutzerZugangZuruecksetzen zugang;
    private final BenutzerGoogleLoesen google;
    private final BenutzerEmailAendern email;
    private final MandantAufloesung mandant;
    private final SecurityIdentity identitaet;
    private final ObjectReader json;
    private final Berechtigung berechtigung;
    private final StepUp stepUp;

    public BenutzerResource(BenutzerLesen lesen, BenutzerEinladen einladen, BenutzerAnlegen anlegen,
                            BenutzerZugangZuruecksetzen zugang, BenutzerGoogleLoesen google, BenutzerEmailAendern email,
                            MandantAufloesung mandant, SecurityIdentity identitaet, ObjectMapper json,
                            Berechtigung berechtigung, StepUp stepUp) {
        this.lesen = lesen;
        this.einladen = einladen;
        this.anlegen = anlegen;
        this.zugang = zugang;
        this.google = google;
        this.email = email;
        this.mandant = mandant;
        this.identitaet = identitaet;
        this.json = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.berechtigung = berechtigung;
        this.stepUp = stepUp;
    }

    @GET
    @ErfordertAktion(Aktion.A21)
    @RolesAllowed("inhaber")
    @Operation(summary = "Benutzer des Mandanten auflisten")
    public List<BenutzerLesen.BenutzerAnsicht> suchen(@QueryParam("q") String suche,
                                              @QueryParam("seite") @DefaultValue("0") int seite,
                                              @QueryParam("groesse") @DefaultValue("50") int groesse) {
        return lesen.suchen(suche, seite, groesse);
    }

    @GET
    @Path("/{benutzerId}")
    @ErfordertAktion(Aktion.A21)
    @RolesAllowed("inhaber")
    @Operation(summary = "Benutzer des Mandanten anzeigen")
    @APIResponse(responseCode = "200", description = "Benutzer des Mandanten",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = BenutzerLesen.BenutzerAnsicht.class)))
    public Response lesen(@PathParam("benutzerId") String benutzerId) {
        BenutzerLesen.BenutzerAnsicht ansicht = lesen.lesen(benutzerId);
        return Response.ok(ansicht).tag(ansicht.etag()).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A22)
    @RolesAllowed("inhaber")
    @Operation(summary = "Benutzer anlegen und einladen")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = BenutzerAnlegen.BenutzerAnlage.class)))
    @APIResponse(responseCode = "201", description = "Benutzer angelegt",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = BenutzerAnlegen.BenutzerAnlageErgebnis.class)))
    public Response anlegen(@HeaderParam("X-Real-Ip") String ip,
                            @HeaderParam("Content-Type") String contentType, InputStream body) throws IOException {
        var code = mandant.pruefen();
        Benutzer akteur = akteur();
        berechtigung.aktion(akteur, Aktion.A23);
        stepUp.pruefen();
        jsonPruefen(contentType);
        byte[] eingabe = begrenztLesen(body);
        BenutzerAnlegen.BenutzerAnlage neu;
        try {
            neu = json.forType(BenutzerAnlegen.BenutzerAnlage.class).readValue(eingabe);
        } catch (JsonProcessingException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return Response.status(Response.Status.CREATED)
                .entity(anlegen.anlegen(code, akteur, neu, IpAdresse.aus(ip))).build();
    }

    @POST
    @Path("/{benutzerId}/einladung")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A22)
    @RolesAllowed("inhaber")
    @Operation(summary = "Einladung erneut senden")
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.OBJECT)))
    @APIResponse(responseCode = "204", description = "Einladung gesendet")
    public Response einladung(@PathParam("benutzerId") String benutzerId, @HeaderParam("X-Real-Ip") String ip,
                              @HeaderParam("Content-Type") String contentType, InputStream body) throws IOException {
        leerenKoerperPruefen(contentType, body);
        einladen.erneut(mandant.pruefen(), akteur(), benutzerId, IpAdresse.aus(ip));
        return Response.noContent().build();
    }

    private void leerenKoerperPruefen(String contentType, InputStream body) throws IOException {
        jsonPruefen(contentType);
        byte[] eingabe = begrenztLesen(body);
        if (eingabe.length > 0) {
            try {
                var wert = json.readTree(eingabe);
                if (wert == null || !wert.isObject() || !wert.isEmpty()) {
                    throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
                }
            } catch (JsonProcessingException e) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
        }
    }

    @POST
    @Path("/{benutzerId}/zugang-zuruecksetzen")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A22)
    @RolesAllowed("inhaber")
    @Operation(summary = "Zugang zurücksetzen")
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.OBJECT)))
    @APIResponse(responseCode = "204", description = "Zugang zurückgesetzt und Einladung gesendet")
    public Response zugangZuruecksetzen(@PathParam("benutzerId") String benutzerId,
                                       @HeaderParam("X-Real-Ip") String ip,
                                       @HeaderParam("Content-Type") String contentType, InputStream body)
            throws IOException {
        var code = mandant.pruefen();
        Benutzer akteur = akteur();
        berechtigung.aktion(akteur, Aktion.A22);
        stepUp.pruefen();
        leerenKoerperPruefen(contentType, body);
        zugang.ausfuehren(code, akteur, benutzerId, IpAdresse.aus(ip));
        return Response.noContent().build();
    }

    @DELETE
    @Path("/{benutzerId}/google-verknuepfung")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A22)
    @RolesAllowed("inhaber")
    @Operation(summary = "Google-Verknüpfung lösen")
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.OBJECT)))
    @APIResponse(responseCode = "204", description = "Google-Verknüpfung gelöst")
    public Response googleLoesen(@PathParam("benutzerId") String benutzerId, @HeaderParam("X-Real-Ip") String ip,
                                 @HeaderParam("Content-Type") String contentType, InputStream body) throws IOException {
        var code = mandant.pruefen();
        Benutzer akteur = akteur();
        berechtigung.aktion(akteur, Aktion.A22);
        stepUp.pruefen();
        leerenKoerperPruefen(contentType, body);
        google.ausfuehren(code, akteur, benutzerId, IpAdresse.aus(ip));
        return Response.noContent().build();
    }

    public record EmailAenderung(String email) {
        @Override
        public String toString() {
            return "EmailAenderung[geschützt]";
        }
    }

    @PATCH
    @Path("/{benutzerId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A22)
    @RolesAllowed("inhaber")
    @Operation(summary = "E-Mail-Adresse ändern")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = EmailAenderung.class)))
    @APIResponse(responseCode = "204", description = "E-Mail-Adresse geändert")
    public Response emailAendern(@PathParam("benutzerId") String benutzerId, @HeaderParam("If-Match") String ifMatch,
                                 @HeaderParam("X-Real-Ip") String ip,
                                 @HeaderParam("Content-Type") String contentType, InputStream body) throws IOException {
        var code = mandant.pruefen();
        Benutzer akteur = akteur();
        berechtigung.aktion(akteur, Aktion.A22);
        stepUp.pruefen();
        BenutzerEmailAendern.ifMatchPruefen(ifMatch);
        jsonPruefen(contentType);
        EmailAenderung aenderung;
        try {
            aenderung = json.forType(EmailAenderung.class).readValue(begrenztLesen(body));
        } catch (JsonProcessingException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if (aenderung == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        email.ausfuehren(code, akteur, benutzerId, ifMatch, aenderung.email(), IpAdresse.aus(ip));
        return Response.noContent().build();
    }

    private Benutzer akteur() {
        Rolle rolle = Rolle.ausRealmRollen(identitaet.getRoles())
                .orElseThrow(() -> new IllegalStateException("Archiv-Rolle fehlt"));
        return new Benutzer(identitaet.getPrincipal().getName(), rolle);
    }

    private static byte[] begrenztLesen(InputStream body) throws IOException {
        byte[] eingabe = body.readNBytes(8193);
        if (eingabe.length > 8192) {
            throw new FachFehler(Fehlercode.DATEI_ZU_GROSS);
        }
        return eingabe;
    }

    private static void jsonPruefen(String contentType) {
        try {
            MediaType typ = MediaType.valueOf(contentType);
            if (typ.getType().equalsIgnoreCase("application") && typ.getSubtype().equalsIgnoreCase("json")) {
                return;
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new FachFehler(Fehlercode.MEDIENTYP_FALSCH);
        }
        throw new FachFehler(Fehlercode.MEDIENTYP_FALSCH);
    }
}
