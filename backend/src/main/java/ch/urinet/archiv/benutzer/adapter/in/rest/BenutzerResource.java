package ch.urinet.archiv.benutzer.adapter.in.rest;

import ch.urinet.archiv.benutzer.application.BenutzerLesen;
import ch.urinet.archiv.benutzer.application.BenutzerEinladen;
import ch.urinet.archiv.benutzer.application.BenutzerAnlegen;
import ch.urinet.archiv.benutzer.application.BenutzerZugangZuruecksetzen;
import ch.urinet.archiv.benutzer.application.BenutzerGoogleLoesen;
import ch.urinet.archiv.benutzer.application.BenutzerAendern;
import ch.urinet.archiv.benutzer.application.BenutzerStatusAendern;
import ch.urinet.archiv.benutzer.application.BenutzerRolleAendern;
import ch.urinet.archiv.benutzer.application.BenutzerAntraege;
import ch.urinet.archiv.benutzer.application.BenutzerLoeschen;
import ch.urinet.archiv.benutzer.application.BenutzerVerweise;
import ch.urinet.archiv.benutzer.application.VerwaltungAntragSpeicher;
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
import jakarta.ws.rs.PUT;
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
import java.util.UUID;

@Path("/benutzer")
@Tag(name = "Benutzerverwaltung")
@Produces(MediaType.APPLICATION_JSON)
public class BenutzerResource {
    private final BenutzerLesen lesen;
    private final BenutzerEinladen einladen;
    private final BenutzerAnlegen anlegen;
    private final BenutzerZugangZuruecksetzen zugang;
    private final BenutzerGoogleLoesen google;
    private final BenutzerAendern aendern;
    private final BenutzerStatusAendern status;
    private final BenutzerRolleAendern rolle;
    private final BenutzerAntraege antraege;
    private final BenutzerLoeschen loeschen;
    private final MandantAufloesung mandant;
    private final SecurityIdentity identitaet;
    private final ObjectReader json;
    private final Berechtigung berechtigung;
    private final StepUp stepUp;

    public BenutzerResource(BenutzerLesen lesen, BenutzerEinladen einladen, BenutzerAnlegen anlegen,
                            BenutzerZugangZuruecksetzen zugang, BenutzerGoogleLoesen google, BenutzerAendern aendern,
                            BenutzerStatusAendern status, BenutzerRolleAendern rolle, BenutzerAntraege antraege,
                            BenutzerLoeschen loeschen,
                            MandantAufloesung mandant, SecurityIdentity identitaet, ObjectMapper json,
                            Berechtigung berechtigung, StepUp stepUp) {
        this.lesen = lesen;
        this.einladen = einladen;
        this.anlegen = anlegen;
        this.zugang = zugang;
        this.google = google;
        this.aendern = aendern;
        this.status = status;
        this.rolle = rolle;
        this.antraege = antraege;
        this.loeschen = loeschen;
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

    @GET
    @Path("/{benutzerId}/rechte")
    @ErfordertAktion(Aktion.A21)
    @RolesAllowed("inhaber")
    @Operation(summary = "Rechte eines Benutzers anzeigen")
    @APIResponse(responseCode = "200", description = "Rolle und Rechte des Benutzers")
    public RollenResource.RolleDto rechte(@PathParam("benutzerId") String benutzerId) {
        var rollen = lesen.lesen(benutzerId).rollen();
        if (rollen.size() != 1) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        return RollenResource.darstellen(rollen.iterator().next());
    }

    public record RollenAenderung(String rolle) {
    }

    @PUT
    @Path("/{benutzerId}/rolle")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A23)
    @RolesAllowed("inhaber")
    @Operation(summary = "Rolle eines Benutzers ändern")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = RollenAenderung.class)))
    @APIResponse(responseCode = "204", description = "Rolle geändert")
    @APIResponse(responseCode = "202", description = "Vier-Augen-Antrag angelegt",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = BenutzerRolleAendern.Ergebnis.class)))
    public Response rolleAendern(@PathParam("benutzerId") String id, @HeaderParam("If-Match") String ifMatch,
                                 @HeaderParam("X-Real-Ip") String ip,
                                 @HeaderParam("Content-Type") String contentType, InputStream body) throws IOException {
        var code = mandant.pruefen();
        Benutzer akteur = akteur();
        berechtigung.aktion(akteur, Aktion.A23);
        stepUp.pruefen();
        jsonPruefen(contentType);
        RollenAenderung eingabe;
        try {
            eingabe = json.forType(RollenAenderung.class).readValue(begrenztLesen(body));
        } catch (JsonProcessingException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if (eingabe == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        BenutzerRolleAendern.Ergebnis ergebnis = rolle.aendern(code, akteur, id, ifMatch, eingabe.rolle(),
                IpAdresse.aus(ip));
        return ergebnis.antragId() == null ? Response.noContent().build()
                : Response.status(Response.Status.ACCEPTED).entity(ergebnis).build();
    }

    @GET
    @Path("/antraege")
    @ErfordertAktion(Aktion.A21)
    @RolesAllowed("inhaber")
    @Operation(summary = "Offene Verwaltungsanträge anzeigen")
    public List<BenutzerAntraege.Ansicht> antraege() {
        mandant.pruefen();
        return antraege.offene();
    }

    @POST
    @Path("/antraege/{antragId}/bestaetigen")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A23)
    @RolesAllowed("inhaber")
    @Operation(summary = "Verwaltungsantrag bestätigen")
    public Response antragBestaetigen(@PathParam("antragId") String id, @HeaderParam("X-Real-Ip") String ip,
                                      @HeaderParam("Content-Type") String contentType, InputStream body)
            throws IOException {
        return antragEntscheiden(id, ip, contentType, body, VerwaltungAntragSpeicher.Status.BESTAETIGT);
    }

    @POST
    @Path("/antraege/{antragId}/ablehnen")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A23)
    @RolesAllowed("inhaber")
    @Operation(summary = "Verwaltungsantrag ablehnen")
    public Response antragAblehnen(@PathParam("antragId") String id, @HeaderParam("X-Real-Ip") String ip,
                                   @HeaderParam("Content-Type") String contentType, InputStream body)
            throws IOException {
        return antragEntscheiden(id, ip, contentType, body, VerwaltungAntragSpeicher.Status.ABGELEHNT);
    }

    @POST
    @Path("/antraege/{antragId}/zurueckziehen")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A23)
    @RolesAllowed("inhaber")
    @Operation(summary = "Eigenen Verwaltungsantrag zurückziehen")
    public Response antragZurueckziehen(@PathParam("antragId") String id, @HeaderParam("X-Real-Ip") String ip,
                                        @HeaderParam("Content-Type") String contentType, InputStream body)
            throws IOException {
        return antragEntscheiden(id, ip, contentType, body, VerwaltungAntragSpeicher.Status.ZURUECKGEZOGEN);
    }

    private Response antragEntscheiden(String id, String ip, String contentType, InputStream body,
                                       VerwaltungAntragSpeicher.Status entscheid) throws IOException {
        var code = mandant.pruefen();
        Benutzer akteur = akteur();
        berechtigung.aktion(akteur, Aktion.A23);
        if (entscheid == VerwaltungAntragSpeicher.Status.BESTAETIGT) {
            stepUp.pruefen();
        }
        leerenKoerperPruefen(contentType, body);
        UUID antragId;
        try {
            antragId = UUID.fromString(id);
            if (!antragId.toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException("Ungültige Kennung");
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        antraege.entscheiden(code, akteur, antragId, entscheid, IpAdresse.aus(ip));
        return Response.noContent().build();
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

    public record BenutzerAenderung(String email, String vorname, String nachname) {
        @Override
        public String toString() {
            return "BenutzerAenderung[geschützt]";
        }
    }

    @PATCH
    @Path("/{benutzerId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A22)
    @RolesAllowed("inhaber")
    @Operation(summary = "E-Mail-Adresse oder Namen ändern")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = BenutzerAenderung.class)))
    @APIResponse(responseCode = "204", description = "Benutzerdaten geändert")
    public Response benutzerAendern(@PathParam("benutzerId") String benutzerId, @HeaderParam("If-Match") String ifMatch,
                                 @HeaderParam("X-Real-Ip") String ip,
                                 @HeaderParam("Content-Type") String contentType, InputStream body) throws IOException {
        var code = mandant.pruefen();
        Benutzer akteur = akteur();
        berechtigung.aktion(akteur, Aktion.A22);
        jsonPruefen(contentType);
        BenutzerAenderung eingabe;
        try {
            eingabe = json.forType(BenutzerAenderung.class).readValue(begrenztLesen(body));
        } catch (JsonProcessingException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if (eingabe == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if (eingabe.email() != null) {
            stepUp.pruefen();
        }
        BenutzerAendern.ifMatchPruefen(ifMatch);
        aendern.ausfuehren(code, akteur, benutzerId, ifMatch, eingabe.email(), eingabe.vorname(),
                eingabe.nachname(), IpAdresse.aus(ip));
        return Response.noContent().build();
    }

    public record Grund(String grund) {
    }

    @GET
    @Path("/{benutzerId}/loeschvorschau")
    @ErfordertAktion(Aktion.A22)
    @RolesAllowed("inhaber")
    @Operation(summary = "Verweise vor dem Löschen anzeigen")
    @APIResponse(responseCode = "200", description = "Anzahl Protokolleinträge und Dokumente",
            content = @Content(schema = @Schema(implementation = BenutzerVerweise.Loeschvorschau.class)))
    public BenutzerVerweise.Loeschvorschau loeschvorschau(@PathParam("benutzerId") String id) {
        return loeschen.vorschau(mandant.pruefen(), akteur(), id);
    }

    @POST
    @Path("/{benutzerId}/loeschen")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A22)
    @RolesAllowed("inhaber")
    @Operation(summary = "Deaktivierten Benutzer nach Ablauf der Frist löschen")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = Grund.class)))
    @APIResponse(responseCode = "204", description = "Benutzer gelöscht")
    @APIResponse(responseCode = "202", description = "Vier-Augen-Antrag angelegt",
            content = @Content(schema = @Schema(implementation = BenutzerLoeschen.Ergebnis.class)))
    public Response loeschen(@PathParam("benutzerId") String id, @HeaderParam("X-Real-Ip") String ip,
                             @HeaderParam("Content-Type") String contentType, InputStream body) throws IOException {
        var code = mandant.pruefen();
        Benutzer akteur = akteur();
        berechtigung.aktion(akteur, Aktion.A22);
        stepUp.pruefen();
        jsonPruefen(contentType);
        Grund eingabe;
        try {
            eingabe = json.forType(Grund.class).readValue(begrenztLesen(body));
        } catch (JsonProcessingException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        var ergebnis = loeschen.loeschen(code, akteur, id, eingabe == null ? null : eingabe.grund(), IpAdresse.aus(ip));
        return ergebnis.antragId() == null ? Response.noContent().build()
                : Response.status(Response.Status.ACCEPTED).entity(ergebnis).build();
    }

    @POST
    @Path("/{benutzerId}/sperren")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A22)
    @RolesAllowed("inhaber")
    @Operation(summary = "Benutzer sperren")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = Grund.class)))
    @APIResponse(responseCode = "204", description = "Benutzer gesperrt")
    public Response sperren(@PathParam("benutzerId") String id, @HeaderParam("X-Real-Ip") String ip,
                            @HeaderParam("Content-Type") String contentType, InputStream body) throws IOException {
        return statusAendern(id, ip, contentType, body, BenutzerStatusAendern.Vorgang.SPERREN);
    }

    @POST
    @Path("/{benutzerId}/entsperren")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A22)
    @RolesAllowed("inhaber")
    @Operation(summary = "Benutzer entsperren")
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.OBJECT)))
    @APIResponse(responseCode = "204", description = "Benutzer entsperrt")
    public Response entsperren(@PathParam("benutzerId") String id, @HeaderParam("X-Real-Ip") String ip,
                               @HeaderParam("Content-Type") String contentType, InputStream body) throws IOException {
        return statusAendern(id, ip, contentType, body, BenutzerStatusAendern.Vorgang.ENTSPERREN);
    }

    @POST
    @Path("/{benutzerId}/deaktivieren")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A22)
    @RolesAllowed("inhaber")
    @Operation(summary = "Benutzer deaktivieren")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = Grund.class)))
    @APIResponse(responseCode = "204", description = "Benutzer deaktiviert")
    public Response deaktivieren(@PathParam("benutzerId") String id, @HeaderParam("X-Real-Ip") String ip,
                                 @HeaderParam("Content-Type") String contentType, InputStream body) throws IOException {
        return statusAendern(id, ip, contentType, body, BenutzerStatusAendern.Vorgang.DEAKTIVIEREN);
    }

    @POST
    @Path("/{benutzerId}/reaktivieren")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A22)
    @RolesAllowed("inhaber")
    @Operation(summary = "Benutzer reaktivieren")
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.OBJECT)))
    @APIResponse(responseCode = "204", description = "Benutzer reaktiviert")
    public Response reaktivieren(@PathParam("benutzerId") String id, @HeaderParam("X-Real-Ip") String ip,
                                 @HeaderParam("Content-Type") String contentType, InputStream body) throws IOException {
        return statusAendern(id, ip, contentType, body, BenutzerStatusAendern.Vorgang.REAKTIVIEREN);
    }

    private Response statusAendern(String id, String ip, String contentType, InputStream body,
                                  BenutzerStatusAendern.Vorgang vorgang) throws IOException {
        var code = mandant.pruefen();
        Benutzer akteur = akteur();
        berechtigung.aktion(akteur, Aktion.A22);
        boolean mitGrund = vorgang == BenutzerStatusAendern.Vorgang.SPERREN
                || vorgang == BenutzerStatusAendern.Vorgang.DEAKTIVIEREN;
        if (!mitGrund) {
            stepUp.pruefen();
        }
        String grund = null;
        if (mitGrund) {
            jsonPruefen(contentType);
            byte[] eingabe = begrenztLesen(body);
            if (eingabe.length != 0) {
                try {
                    Grund wert = json.forType(Grund.class).readValue(eingabe);
                    grund = wert == null ? null : wert.grund();
                } catch (JsonProcessingException e) {
                    throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
                }
            }
        } else {
            leerenKoerperPruefen(contentType, body);
        }
        status.ausfuehren(code, akteur, id, vorgang, grund, IpAdresse.aus(ip));
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
