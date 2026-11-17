package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentFinanz;
import ch.urinet.archiv.dokument.domain.DokumentVertrag;
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
import jakarta.ws.rs.PATCH;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import ch.urinet.archiv.dokument.application.DokumentLesen;
import ch.urinet.archiv.dokument.application.DokumentWiederholen;
import ch.urinet.archiv.dokument.application.FassungsBearbeiter;
import ch.urinet.archiv.dokumentart.application.DokumentartVerwaltung;
import ch.urinet.archiv.integration.application.webseite.DateiAuslieferung;
import ch.urinet.archiv.integration.application.webseite.WebseitenPflege;
import ch.urinet.archiv.integration.domain.webseite.Grenzklasse;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.gemeinsam.adapter.in.rest.Versionskopf;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.core.StreamingOutput;

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

    private final WebseitenPflege pflege;
    private final DokumentLesen lesen;
    private final DokumentWiederholen wiederholen;
    private final DokumentartVerwaltung arten;
    private final FassungsBearbeiter fassungsBearbeiter;
    private final Berechtigung berechtigung;
    private final DateiAuslieferung auslieferung;
    private final MandantAufloesung aufloesung;

    public WebseitenErfassungResource(WebseitenErfassung erfassung, SecurityIdentity identitaet, ObjectMapper json,
                                      WebseitenPflege pflege, DokumentLesen lesen, DokumentWiederholen wiederholen,
                                      DokumentartVerwaltung arten, FassungsBearbeiter fassungsBearbeiter,
                                      Berechtigung berechtigung, DateiAuslieferung auslieferung,
                                      MandantAufloesung aufloesung) {
        this.erfassung = erfassung;
        this.identitaet = identitaet;
        this.json = json;
        this.pflege = pflege;
        this.lesen = lesen;
        this.wiederholen = wiederholen;
        this.arten = arten;
        this.fassungsBearbeiter = fassungsBearbeiter;
        this.berechtigung = berechtigung;
        this.auslieferung = auslieferung;
        this.aufloesung = aufloesung;
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
            @Schema(nullable = true) String ausgabe,
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
            String projekt, String zugriff, Boolean veroeffentlicht, DokumentFinanz finanz, DokumentVertrag vertrag) {
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

    @Schema(name = "WebseiteErfassungArt")
    public record ArtDto(String code, String name, List<String> gruppen, List<String> pflichtfelder,
                         @Schema(type = SchemaType.OBJECT) JsonNode zusatzSchema, String vertraulichkeit) {
    }

    @Schema(name = "WebseiteErfassungMetadatenAenderung", additionalProperties = Schema.False.class)
    public record AenderungDto(String art, String titel, LocalDate datum, UUID partnerId, String projekt,
                               List<String> tags, String vertraulichkeit, Integer geschaeftsjahr,
                               String belegnummer, String richtung, BigDecimal betrag, String waehrung,
                               LocalDate faelligkeit, String qrReferenz, String konto, LocalDate gueltigAb,
                               LocalDate gueltigBis, String kuendigungsfrist, LocalDate naechsteFrist,
                               @Schema(type = SchemaType.OBJECT,
                                       description = "Formularfelder ohne veroeffentlicht, geprüft gegen das Art-Schema")
                               JsonNode zusatz, String grund) {
    }

    @Schema(name = "WebseiteErfassungBestaetigung", additionalProperties = Schema.False.class)
    public record BestaetigungDto(String art, String titel, LocalDate datum, UUID partnerId, String projekt,
                                  List<String> tags, String vertraulichkeit, String personBenutzer, Integer geschaeftsjahr,
                                  String belegnummer, String richtung, BigDecimal betrag, String waehrung,
                                  LocalDate faelligkeit, String qrReferenz, String konto, LocalDate gueltigAb,
                                  LocalDate gueltigBis, String kuendigungsfrist, LocalDate naechsteFrist,
                                  @Schema(type = SchemaType.OBJECT,
                                          description = "Formularfelder ohne veroeffentlicht, geprüft gegen das Art-Schema")
                                  JsonNode zusatz, Boolean ohnePdfa, Boolean fristAbgelaufenAkzeptiert) {
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

    @GET
    @Path("/erfassung/eingangskorb")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    @WebseitenErfassungAktion(Aktion.A5)
    @Operation(summary = "Eingangskorb des Mandanten ohne Dateinamen")
    @APIResponse(responseCode = "200", description = "Sichtbare Dokumente im Eingangskorb",
            content = @Content(schema = @Schema(implementation = ListeDto.class)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public ListeDto eingangskorb(@QueryParam("seite") @DefaultValue("0") int seite,
                                 @QueryParam("groesse") @DefaultValue("20") int groesse) {
        var treffer = lesen.eingangskorb(benutzer(), seite, groesse);
        return new ListeDto(treffer.eintraege().stream()
                .map(d -> dokumentDto(d, erfassung.referenz(webseite(), d.id()).orElse(null))).toList(),
                seite, groesse, treffer.gesamt());
    }

    @GET
    @Path("/erfassung/dokumentarten")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    @WebseitenErfassungAktion(Aktion.A5)
    @Operation(summary = "Aktive Dokumentarten des Mandanten mit Formularfeldern")
    @APIResponse(responseCode = "200", description = "Arten",
            content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = ArtDto.class)))
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public List<ArtDto> dokumentarten() {
        aufloesung.pruefen();
        return arten.alle().stream().filter(a -> a.aktiv())
                .map(a -> new ArtDto(a.code(), a.name(), a.gruppen(), a.pflichtfelder(),
                        json(a.zusatzSchema()), a.vertraulichkeit().name())).toList();
    }

    @PATCH
    @Path("/erfassung/dokumente/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    @WebseitenErfassungAktion(Aktion.A6)
    @Protokolliert(aktion = ProtokollAktion.AENDERN)
    @Operation(summary = "Metadaten ändern",
            description = "Felder wie in der Web-App, ausser personBenutzer, dateiname und zusatz.veroeffentlicht. "
                    + "Unbekannte Felder und ungültige Typen sind 400. Zusatz ersetzt die bisherigen Formularfelder; "
                    + "veroeffentlicht bleibt erhalten. Bei ARCHIVIERT ist grund Pflicht.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AenderungDto.class)))
    @APIResponse(responseCode = "200", description = "Geändertes Dokument",
            content = @Content(schema = @Schema(implementation = DetailsDto.class)),
            headers = @Header(name = "ETag", schema = @Schema(type = SchemaType.STRING)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    @APIResponse(responseCode = "409", description = "Status- oder Sperrkonflikt")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "422", description = "Pflichtfelder oder Zusatzfelder ungültig")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public Response aendern(@PathParam("id") UUID id,
                            @HeaderParam("If-Match") @Parameter(required = true) String ifMatch,
                            @HeaderParam("X-Archiv-Bearbeiter") @Parameter(required = true) String bearbeiter,
                            String inhalt) {
        fassungsBearbeiter.setzen(webseite() + "/" + bearbeiter);
        var parser = new ErfassungsMetadaten(json);
        Dokument d = pflege.aendern(benutzer(), id, Versionskopf.version(ifMatch), false, () -> {
            ObjectNode eingabe = parser.lesen(inhalt, false);
            if (eingabe.has("vertraulichkeit")) {
                berechtigung.dokumentAktion(benutzer(), Aktion.A8, id);
            }
            veroeffentlichtErhalten(eingabe, id);
            return parser.aenderung(eingabe);
        });
        return dokumentAntwort(d, false);
    }

    @POST
    @Path("/erfassung/dokumente/{id}/bestaetigen")
    @Consumes(MediaType.APPLICATION_JSON)
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    @WebseitenErfassungAktion(Aktion.A7)
    @Protokolliert(aktion = ProtokollAktion.BESTAETIGEN)
    @Operation(summary = "Dokument mit denselben Pflichtfeldern wie die Web-App bestätigen",
            description = "Felder der Bestätigung der Web-App ohne dateiname, zusätzlich zusatz. "
                    + "zusatz.veroeffentlicht und unbekannte Felder sind 400. Zusatz wird atomar mitbestätigt.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = BestaetigungDto.class)))
    @APIResponse(responseCode = "202", description = "Archivierung eingereiht",
            content = @Content(schema = @Schema(implementation = DetailsDto.class)),
            headers = @Header(name = "ETag", schema = @Schema(type = SchemaType.STRING)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    @APIResponse(responseCode = "409", description = "Status-, Frist- oder Belegnummernkonflikt")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "422", description = "Pflichtfelder oder Zusatzfelder ungültig")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public Response bestaetigen(@PathParam("id") UUID id,
                                @HeaderParam("If-Match") @Parameter(required = true) String ifMatch,
                                @HeaderParam("X-Archiv-Bearbeiter") @Parameter(required = true) String bearbeiter,
                                String inhalt) {
        var parser = new ErfassungsMetadaten(json);
        Dokument d = pflege.bestaetigen(benutzer(), id, Versionskopf.version(ifMatch), () -> {
            ObjectNode eingabe = parser.lesen(inhalt, true);
            if (eingabe.has("vertraulichkeit") || eingabe.has("personBenutzer")) {
                berechtigung.dokumentAktion(benutzer(), Aktion.A8, id);
            }
            var bestaetigung = parser.bestaetigung(eingabe);
            ch.urinet.archiv.dokument.application.Aenderung zusatz = null;
            if (eingabe.has("zusatz")) {
                ObjectNode patch = json.createObjectNode();
                patch.set("zusatz", eingabe.get("zusatz"));
                patch.put("art", bestaetigung.art());
                veroeffentlichtErhalten(patch, id);
                zusatz = parser.aenderung(patch);
            }
            return new WebseitenPflege.Bestaetigungsangaben(bestaetigung, zusatz);
        });
        return dokumentAntwort(d, true);
    }

    @POST
    @Path("/erfassung/dokumente/{id}/veroeffentlichen")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    @WebseitenErfassungAktion(Aktion.A6)
    @Protokolliert(aktion = ProtokollAktion.AENDERN)
    @Operation(summary = "Archiviertes Dokument veröffentlichen")
    @APIResponse(responseCode = "200", description = "Veröffentlicht",
            content = @Content(schema = @Schema(implementation = DetailsDto.class)),
            headers = @Header(name = "ETag", schema = @Schema(type = SchemaType.STRING)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    @APIResponse(responseCode = "409", description = "STATUS_KONFLIKT")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "422", description = "Zusatzfelder der Dokumentart ungültig")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public Response veroeffentlichen(@PathParam("id") UUID id,
                                     @HeaderParam("If-Match") @Parameter(required = true) String ifMatch,
                                     @HeaderParam("X-Archiv-Bearbeiter") @Parameter(required = true) String bearbeiter) {
        return veroeffentlichung(id, ifMatch, bearbeiter, true);
    }

    @POST
    @Path("/erfassung/dokumente/{id}/zurueckziehen")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    @WebseitenErfassungAktion(Aktion.A6)
    @Protokolliert(aktion = ProtokollAktion.AENDERN)
    @Operation(summary = "Archiviertes Dokument zurückziehen, nicht löschen")
    @APIResponse(responseCode = "200", description = "Zurückgezogen",
            content = @Content(schema = @Schema(implementation = DetailsDto.class)),
            headers = @Header(name = "ETag", schema = @Schema(type = SchemaType.STRING)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    @APIResponse(responseCode = "409", description = "STATUS_KONFLIKT")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "422", description = "Zusatzfelder der Dokumentart ungültig")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public Response zurueckziehen(@PathParam("id") UUID id,
                                  @HeaderParam("If-Match") @Parameter(required = true) String ifMatch,
                                  @HeaderParam("X-Archiv-Bearbeiter") @Parameter(required = true) String bearbeiter) {
        return veroeffentlichung(id, ifMatch, bearbeiter, false);
    }

    @POST
    @Path("/erfassung/dokumente/{id}/wiederholen")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    @WebseitenErfassungAktion(Aktion.A10)
    @Protokolliert(aktion = ProtokollAktion.WIEDERHOLEN)
    @Operation(summary = "Verarbeitung oder Archivierung wiederholen")
    @APIResponse(responseCode = "202", description = "Auftrag eingereiht",
            headers = @Header(name = "ETag", schema = @Schema(type = SchemaType.STRING)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    @APIResponse(responseCode = "409", description = "STATUS_KONFLIKT")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public Response wiederholen(@PathParam("id") UUID id,
                                @HeaderParam("If-Match") @Parameter(required = true) String ifMatch,
                                @HeaderParam("X-Archiv-Bearbeiter") @Parameter(required = true) String bearbeiter) {
        int neu = wiederholen.wiederholen(benutzer(), id, Versionskopf.version(ifMatch));
        return Response.accepted().tag(new EntityTag(Integer.toString(neu))).build();
    }

    @GET
    @Path("/erfassung/dokumente/{id}/datei")
    @Produces(MediaType.WILDCARD)
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.ERFASSUNG)
    @WebseitenErfassungAktion(Aktion.A2)
    @WebseitenGrenze(Grenzklasse.DATEI)
    @BearbeiterErforderlich
    @Operation(summary = "Datei zur Prüfung streamen, nie eine Speicher-URL",
            description = "PDF/A, sonst erstes Original. HEAD ist 405 ohne Stream und Protokoll. Kein Range.")
    @APIResponse(responseCode = "200", description = "Dateistream",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM,
                    schema = @Schema(type = SchemaType.STRING, format = "binary")))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "SpeicherNichtErreichbar")
    public Response datei(@PathParam("id") UUID id,
                          @HeaderParam("X-Archiv-Bearbeiter") @Parameter(required = true) String bearbeiter,
                          @HeaderParam("X-Real-Ip") @Parameter(hidden = true) String ip,
                          @Context HttpServerRequest http) {
        if (http.method() == io.vertx.core.http.HttpMethod.HEAD) {
            return Response.status(Response.Status.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "GET").build();
        }
        var strom = auslieferung.erfassung(aufloesung.pruefen(), benutzer(), id, IpAdresse.aus(ip),
                webseite() + "/" + bearbeiter, () -> http.response().close());
        try {
            StreamingOutput ausgabe = strom.lauf()::senden;
            return Response.ok(ausgabe, strom.medientyp())
                    .header(HttpHeaders.CONTENT_LENGTH, strom.lauf().laenge())
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            strom.medientyp().equals("application/octet-stream") ? "attachment" : "inline")
                    .header("Accept-Ranges", "none").build();
        } catch (RuntimeException e) {
            strom.lauf().abbrechen();
            throw e;
        }
    }

    private Response veroeffentlichung(UUID id, String ifMatch, String bearbeiter, boolean wert) {
        fassungsBearbeiter.setzen(webseite() + "/" + bearbeiter);
        Dokument d = pflege.aendern(benutzer(), id, Versionskopf.version(ifMatch), true, () -> {
            Dokument aktuell = erfassung.detail(benutzer(), id);
            JsonNode bisher = json(aktuell.zusatz());
            ObjectNode zusatz = bisher instanceof ObjectNode objekt ? objekt.deepCopy() : json.createObjectNode();
            zusatz.put("veroeffentlicht", wert);
            ObjectNode patch = json.createObjectNode();
            patch.set("zusatz", zusatz);
            patch.put("grund", wert ? "über Webseite veröffentlicht" : "über Webseite zurückgezogen");
            return new ErfassungsMetadaten(json).aenderung(patch);
        });
        return dokumentAntwort(d, false);
    }

    private void veroeffentlichtErhalten(ObjectNode eingabe, UUID id) {
        if (!eingabe.has("zusatz")) {
            return;
        }
        Dokument bisher = erfassung.detail(benutzer(), id);
        String art = eingabe.hasNonNull("art") ? eingabe.path("art").asText() : bisher.art();
        JsonNode alt = json(bisher.zusatz());
        JsonNode flag = alt == null ? null : alt.get("veroeffentlicht");
        boolean kenntFlag = art != null && json(arten.finden(art).zusatzSchema()).path("properties")
                .has("veroeffentlicht");
        if (flag != null || kenntFlag) {
            JsonNode wert = eingabe.get("zusatz");
            ObjectNode zusatz = wert instanceof ObjectNode objekt ? objekt : json.createObjectNode();
            zusatz.set("veroeffentlicht", flag == null ? json.getNodeFactory().booleanNode(false) : flag);
            eingabe.set("zusatz", zusatz);
        }
    }

    private Response dokumentAntwort(Dokument d, boolean angenommen) {
        Object dto = detailsDto(d, erfassung.referenz(webseite(), d.id()).orElse(null));
        return (angenommen ? Response.accepted(dto) : Response.ok(dto)).tag(etag(d)).build();
    }

    static UploadDto uploadDto(Erfassungsergebnis ergebnis) {
        Dokument dokument = ergebnis.dokument();
        return new UploadDto(dokument == null ? null : dokument.id(), ergebnis.referenz(),
                dokument == null ? null : dokument.status().name(), fehlercode(dokument),
                dokument == null ? null : dokument.version(), ergebnis.dublette());
    }

    private DokumentDto dokumentDto(Dokument dokument, String referenz) {
        JsonNode zusatz = json(dokument.zusatz());
        return new DokumentDto(dokument.id(), referenz, dokument.titel(), dokument.art(), text(zusatz, "ausgabe"), jahr(zusatz),
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
                bool(zusatz, "veroeffentlicht"), d.finanz(), d.vertrag());
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
