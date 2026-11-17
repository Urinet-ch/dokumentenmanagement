package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.application.webseite.WebseitenErfassung;
import ch.urinet.archiv.integration.application.webseite.WebseitenErfassung.Erfassungsergebnis;
import ch.urinet.archiv.integration.application.webseite.WebseitenErfassung.Liste;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.TokenIdentitaet;
import ch.urinet.archiv.sicherheit.application.WebseitenErfassungAktion;
import ch.urinet.archiv.sicherheit.application.WebseitenZugang;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.server.multipart.FormValue;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Upload, Status und Liste der Webseiten-Erfassung; Dateinamen werden nie ausgegeben (Spec 03, Req 10). */
@Path("/public/v1")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "Webseite Erfassung")
public class WebseitenErfassungResource {

    private static final Set<String> FORMULARTEILE = Set.of("datei", "referenz", "hinweis");

    private final WebseitenErfassung erfassung;
    private final SecurityIdentity identitaet;
    private final ObjectMapper json;

    public WebseitenErfassungResource(WebseitenErfassung erfassung, SecurityIdentity identitaet, ObjectMapper json) {
        this.erfassung = erfassung;
        this.identitaet = identitaet;
        this.json = json;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "WebseiteErfassungUpload")
    public record UploadDto(
            @Schema(nullable = true) UUID id,
            @Schema(required = true) String referenz,
            @Schema(nullable = true) String status,
            @Schema(nullable = true) String fehlercode,
            @Schema(nullable = true) Integer version,
            @Schema(required = true) Boolean dublette) {
    }

    @Schema(name = "WebseiteErfassungUploadAnfrage")
    public record UploadAnfrageDto(
            @Schema(required = true, type = SchemaType.STRING, format = "binary") String datei,
            @Schema(required = true, description = "Referenz der Webseite, 1 bis 100 Zeichen")
            String referenz,
            @Schema(nullable = true, maxLength = 1000) String hinweis) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "WebseiteErfassungDokument")
    public record DokumentDto(
            @Schema(required = true) UUID id,
            @Schema(nullable = true) String referenz,
            @Schema(nullable = true) String titel,
            @Schema(nullable = true) String reihe,
            @Schema(nullable = true) Integer jahr,
            @Schema(required = true) String status,
            @Schema(nullable = true) String zugriff,
            @Schema(nullable = true) Boolean veroeffentlicht,
            @Schema(required = true) Integer version) {
    }

    @Schema(name = "WebseiteErfassungListe")
    public record ListeDto(
            @Schema(required = true) List<DokumentDto> eintraege,
            @Schema(required = true) int seite,
            @Schema(required = true) int proSeite,
            @Schema(required = true) long gesamt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "WebseiteErfassungDetails")
    public record DetailsDto(
            UUID id, String referenz, String art, String titel, LocalDate datum, Instant eingang, String quelle,
            List<String> tags, String vertraulichkeit, String personBenutzer, String erstelltVon, String status,
            Instant bestaetigt, Integer geschaeftsjahr, LocalDate aufbewahrenBis, LocalDate sperreBis,
            boolean fristAbgelaufen, JsonNode zusatz, int version, String fehlercode, boolean pdfaFehlt,
            Integer seiten, UUID partnerId, String partnerName, JsonNode vorschlag, JsonNode kiSicherheit,
            String projekt, String zugriff, Boolean veroeffentlicht) {
    }

    @Schema(name = "WebseiteErfassungStatus")
    public record StatusDto(
            @Schema(required = true) UUID id,
            @Schema(required = true) String referenz,
            @Schema(required = true) String status,
            @Schema(nullable = true) String fehlercode,
            @Schema(required = true) int version) {
    }

    @Schema(name = "WebseiteErfassungBearbeiter")
    public record BearbeiterDto(@Schema(required = true, minLength = 1, maxLength = 200) String name) {
    }

    @POST
    @Path("/erfassung/dokumente")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    @WebseitenErfassungAktion(Aktion.A1)
    @WebseitenGrenze(ch.urinet.archiv.integration.domain.webseite.Grenzklasse.UPLOAD)
    @Protokolliert(aktion = ProtokollAktion.ERFASSEN)
    @Transactional
    @TransactionConfiguration(timeoutFromConfigProperty = "archiv.upload.transaktion-zeitgrenze")
    @Operation(summary = "Dokument über eine Webseite erfassen",
            description = "Nimmt genau eine Datei als Multipart-Teil datei an; URLs und weitere Teile sind unzulässig.")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA,
            schema = @Schema(implementation = UploadAnfrageDto.class)))
    @APIResponse(responseCode = "202", description = "Dokument angelegt",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = UploadDto.class)),
            headers = {@Header(name = "Location", schema = @Schema(type = SchemaType.STRING)),
                    @Header(name = "ETag", schema = @Schema(type = SchemaType.STRING))})
    @APIResponse(responseCode = "200", description = "Idempotenter Upload oder Dublette",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = UploadDto.class)),
            headers = {@Header(name = "Location", schema = @Schema(type = SchemaType.STRING)),
                    @Header(name = "ETag", schema = @Schema(type = SchemaType.STRING))})
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "409", ref = "ReferenzVergeben")
    @APIResponse(responseCode = "413", ref = "ZuGross")
    @APIResponse(responseCode = "415", ref = "DateitypNichtUnterstuetzt")
    @APIResponse(responseCode = "422", ref = "DateiUngueltig")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "SpeicherNichtErreichbar")
    public Response hochladen(
            @RestForm("datei") FileUpload datei,
            @RestForm("referenz") String referenz,
            @RestForm("hinweis") String hinweis,
            @HeaderParam("X-Archiv-Bearbeiter")
            @Parameter(in = ParameterIn.HEADER, required = true, description = "Zuvor gemeldete Bearbeiterkennung")
            String bearbeiter,
            @Parameter(hidden = true) MultipartFormDataInput formular) {
        formularPruefen(formular, datei, referenz, hinweis);
        Erfassungsergebnis ergebnis = erfassung.hochladen(webseite(), referenz, datei.uploadedFile(),
                datei.fileName(), hinweis, benutzer());
        UploadDto antwort = uploadDto(ergebnis);
        Response.ResponseBuilder response = ergebnis.dublette() || ergebnis.idempotent()
                ? Response.ok(antwort) : Response.accepted(antwort);
        if (ergebnis.dokument() != null) {
            response.tag(new EntityTag(Integer.toString(ergebnis.dokument().version())))
                    .header(HttpHeaders.LOCATION,
                            "/public/v1/erfassung/dokumente/" + ergebnis.dokument().id());
        }
        return response.build();
    }

    @PUT
    @Path("/erfassung/bearbeiter/{kennung}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    @WebseitenErfassungAktion(Aktion.A1)
    @Operation(summary = "Bearbeiter der Webseite melden")
    @APIResponse(responseCode = "204", description = "Bearbeiter neu oder mit geändertem Namen gemeldet")
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public Response bearbeiterMelden(@PathParam("kennung") String kennung, BearbeiterDto eingabe) {
        erfassung.bearbeiterMelden(webseite(), kennung, eingabe == null ? null : eingabe.name(),
                identitaet.getPrincipal().getName());
        return Response.noContent().build();
    }

    @GET
    @Path("/erfassung/dokumente")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    @WebseitenErfassungAktion(Aktion.A2)
    @Operation(summary = "Erfasste Dokumente auflisten oder Status über Referenz abrufen")
    @APIResponse(responseCode = "200", description = "Liste oder Status",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(oneOf = {ListeDto.class, StatusDto.class})))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public Response liste(
            @QueryParam("referenz") @Parameter(description = "Status für die Referenz der Webseite")
            String referenz,
            @QueryParam("status") @Parameter(description = "Statusfilter")
            String status,
            @QueryParam("reihe") @Parameter(description = "Code der Dokumentart")
            String reihe,
            @QueryParam("jahr") @Parameter(description = "Jahr aus den Zusatzfeldern")
            Integer jahr,
            @QueryParam("veroeffentlicht") @Parameter(description = "Filter für den Veröffentlichungsstatus")
            String veroeffentlicht,
            @QueryParam("q") @Parameter(description = "Suchbegriff, höchstens 200 Zeichen")
            String begriff,
            @QueryParam("seite") @DefaultValue("0")
            @Parameter(description = "Seite ab 0", schema = @Schema(type = SchemaType.INTEGER, format = "int32"))
            int seite,
            @QueryParam("groesse") @DefaultValue("20")
            @Parameter(description = "Einträge je Seite, 1 bis 100",
                    schema = @Schema(type = SchemaType.INTEGER, format = "int32", minimum = "1", maximum = "100"))
            int groesse,
            @QueryParam("dateiname") @Parameter(description = "Nicht unterstützt; führt zu 400")
            String dateinameQuery,
            @Context UriInfo uriInfo) {
        if (uriInfo.getQueryParameters().containsKey("dateiname")) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if (referenz != null) {
            if (uriInfo.getQueryParameters().keySet().stream().anyMatch(k -> !k.equals("referenz"))) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            Dokument dokument = erfassung.status(benutzer(), webseite(), referenz);
            return Response.ok(statusDto(dokument, referenz)).tag(etag(dokument)).build();
        }
        Liste ergebnis = erfassung.liste(benutzer(), webseite(), status, reihe, jahr,
                bool(veroeffentlicht), begriff, seite, groesse);
        List<DokumentDto> eintraege = ergebnis.dokumente().stream()
                .map(d -> dokumentDto(d, ergebnis.referenzen().get(d.id()))).toList();
        return Response.ok(new ListeDto(eintraege, seite, groesse, ergebnis.gesamt())).build();
    }

    @GET
    @Path("/erfassung/dokumente/{id}")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    @WebseitenErfassungAktion(Aktion.A2)
    @Protokolliert(aktion = ProtokollAktion.ANZEIGEN)
    @Operation(summary = "Erfasstes Dokument ohne Dateinamen anzeigen")
    @APIResponse(responseCode = "200", description = "Dokumentdetails",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = DetailsDto.class)),
            headers = @Header(name = "ETag", schema = @Schema(type = SchemaType.STRING)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public Response detail(
            @PathParam("id") UUID id,
            @HeaderParam("X-Archiv-Bearbeiter")
            @Parameter(in = ParameterIn.HEADER, required = true, description = "Zuvor gemeldete Bearbeiterkennung")
            String bearbeiter) {
        Dokument dokument = erfassung.detail(benutzer(), id);
        return Response.ok(detailsDto(dokument, erfassung.referenz(webseite(), id).orElse(null)))
                .tag(etag(dokument)).build();
    }

    private UploadDto uploadDto(Erfassungsergebnis ergebnis) {
        Dokument dokument = ergebnis.dokument();
        return new UploadDto(dokument == null ? null : dokument.id(), ergebnis.referenz(),
                dokument == null ? null : dokument.status().name(), fehlercode(dokument),
                dokument == null ? null : dokument.version(), ergebnis.dublette());
    }

    private DokumentDto dokumentDto(Dokument dokument, String referenz) {
        JsonNode zusatz = json(dokument.zusatz());
        return new DokumentDto(dokument.id(), referenz, dokument.titel(), dokument.art(), jahr(zusatz),
                dokument.status().name(), text(zusatz, "zugriff"), bool(zusatz, "veroeffentlicht"),
                dokument.version());
    }

    private DetailsDto detailsDto(Dokument d, String referenz) {
        JsonNode zusatz = json(d.zusatz());
        return new DetailsDto(d.id(), referenz, d.art(), d.titel(), d.datum(), d.eingang(), d.quelle(), d.tags(),
                d.wirksameVertraulichkeit(), d.personBenutzer(), d.erstelltVon(), d.status().name(), d.bestaetigt(),
                d.geschaeftsjahr(), d.aufbewahrenBis(), d.sperreBis(), d.fristAbgelaufen(), zusatz, d.version(),
                fehlercode(d), d.pdfaFehlt(), d.seiten(), d.partnerId(), d.partnerName(),
                json(d.kiVorschlag()), json(d.kiSicherheit()), d.projekt(), text(zusatz, "zugriff"),
                bool(zusatz, "veroeffentlicht"));
    }

    private StatusDto statusDto(Dokument dokument, String referenz) {
        return new StatusDto(dokument.id(), referenz, dokument.status().name(), fehlercode(dokument),
                dokument.version());
    }

    private static String fehlercode(Dokument dokument) {
        if (dokument == null || !(dokument.status().name().equals("FEHLER")
                || dokument.status().name().equals("ARCHIVIERUNG_FEHLER"))) {
            return null;
        }
        if (dokument.fehler() != null) {
            try {
                return Fehlercode.valueOf(dokument.fehler()).name();
            } catch (IllegalArgumentException ignored) {
                return Fehlercode.INTERNER_FEHLER.name();
            }
        }
        return Fehlercode.INTERNER_FEHLER.name();
    }

    private JsonNode json(String wert) {
        if (wert == null) {
            return null;
        }
        try {
            return json.readTree(wert);
        } catch (IOException e) {
            throw new IllegalStateException("Dokument-Metadaten sind nicht lesbar", e);
        }
    }

    private static Integer jahr(JsonNode zusatz) {
        JsonNode wert = zusatz == null ? null : zusatz.get("jahr");
        return wert != null && wert.isIntegralNumber() && wert.canConvertToInt() ? wert.intValue() : null;
    }

    private static String text(JsonNode zusatz, String feld) {
        JsonNode wert = zusatz == null ? null : zusatz.get(feld);
        return wert != null && wert.isTextual() ? wert.textValue() : null;
    }

    private static Boolean bool(JsonNode zusatz, String feld) {
        JsonNode wert = zusatz == null ? null : zusatz.get(feld);
        return wert != null && wert.isBoolean() ? wert.booleanValue() : null;
    }

    private static Boolean bool(String wert) {
        if (wert == null) {
            return null;
        }
        if (wert.equals("true")) {
            return true;
        }
        if (wert.equals("false")) {
            return false;
        }
        throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }

    private static EntityTag etag(Dokument dokument) {
        return new EntityTag(Integer.toString(dokument.version()));
    }

    private String webseite() {
        String webseite = identitaet.getAttribute(TokenIdentitaet.WEBSEITE);
        if (webseite == null) {
            throw new FachFehler(Fehlercode.NICHT_ERLAUBT);
        }
        return webseite;
    }

    private Benutzer benutzer() {
        return new Benutzer(identitaet.getPrincipal().getName(), Rolle.GESCHAEFTSSTELLE);
    }

    private static void formularPruefen(MultipartFormDataInput formular, FileUpload datei, String referenz,
                                        String hinweis) {
        if (formular == null || datei == null || referenz == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Map<String, Collection<FormValue>> teile = formular.getValues();
        if (!FORMULARTEILE.containsAll(teile.keySet()) || !genauEinTeil(teile, "datei", true)
                || !genauEinTeil(teile, "referenz", false) || !optionalerText(teile, "hinweis", hinweis)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    private static boolean genauEinTeil(Map<String, Collection<FormValue>> teile, String name, boolean datei) {
        Collection<FormValue> werte = teile.get(name);
        return werte != null && werte.size() == 1 && werte.iterator().next().isFileItem() == datei;
    }

    private static boolean optionalerText(Map<String, Collection<FormValue>> teile, String name, String wert) {
        Collection<FormValue> werte = teile.get(name);
        if (werte == null) {
            return wert == null;
        }
        return werte.size() == 1 && !werte.iterator().next().isFileItem() && wert != null;
    }

}
