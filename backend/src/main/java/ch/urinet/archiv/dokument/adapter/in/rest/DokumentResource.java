package ch.urinet.archiv.dokument.adapter.in.rest;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.dokument.application.Aenderung;
import ch.urinet.archiv.dokument.application.Bestaetigung;
import ch.urinet.archiv.dokument.application.DokumentAendern;
import ch.urinet.archiv.dokument.application.DokumentBestaetigen;
import ch.urinet.archiv.dokument.application.DokumentDatei;
import ch.urinet.archiv.dokument.application.DokumentLesen;
import ch.urinet.archiv.dokument.application.DokumentVerwerfen;
import ch.urinet.archiv.dokument.application.DokumentWiederholen;
import ch.urinet.archiv.dokument.application.Warnung;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentFinanz;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.dokument.domain.DokumentVertrag;
import ch.urinet.archiv.dokument.domain.KiSicherheit;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dokument lesen und herunterladen (A2–A4, Req 1.4, 2.3, 2.21, 14.6); den Eingangskorb liest die
 * {@link EingangskorbResource}, die Suche die {@code SucheResource}. Sichtbarkeit
 * und Recht prüft der {@code ZugangFilter} vor dem Lesen (404 vor 403); die Abfragen filtern zusätzlich nach
 * Vertraulichkeit. Ändern (A6, mit A8 für Vertraulichkeit und {@code personBenutzer}), Bestätigen (A7), Verwerfen
 * (A9) und Wiederholen (A10) verlangen If-Match (fehlt → 428, veraltet → 412, Req 8.8, 10.4, 10.6).
 */
@Path("/dokumente")
@Tag(name = "Dokumente")
@Produces(MediaType.APPLICATION_JSON)
public class DokumentResource {

    // nur ein starkes ETag passt (RFC 9110, If-Match vergleicht stark)
    private static final Pattern VERSION = Pattern.compile("^\"(\\d{1,9})\"$");
    private static final String SICHERHEIT = "sicherheit";

    private static final String GRUND = "grund";

    private final DokumentLesen lesen;
    private final DokumentDatei datei;
    private final DokumentAendern aendern;
    private final DokumentBestaetigen bestaetigen;
    private final DokumentVerwerfen verwerfen;
    private final DokumentWiederholen wiederholen;
    private final MandantAufloesung aufloesung;
    private final Berechtigung berechtigung;
    private final SecurityIdentity identitaet;
    private final ObjectMapper json;

    public DokumentResource(DokumentLesen lesen, DokumentDatei datei, DokumentAendern aendern,
                            DokumentBestaetigen bestaetigen, DokumentVerwerfen verwerfen,
                            DokumentWiederholen wiederholen, MandantAufloesung aufloesung,
                            Berechtigung berechtigung, SecurityIdentity identitaet, ObjectMapper json) {
        this.lesen = lesen;
        this.datei = datei;
        this.aendern = aendern;
        this.bestaetigen = bestaetigen;
        this.verwerfen = verwerfen;
        this.wiederholen = wiederholen;
        this.aufloesung = aufloesung;
        this.berechtigung = berechtigung;
        this.identitaet = identitaet;
        this.json = json;
    }

    @Schema(name = "Warnung", description = "Hinweis, der die Bestätigung nicht verhindert (Req 8.10)")
    public record WarnungDto(
            @Schema(required = true, enumeration = {"BELEGNUMMER_BEKANNT"}) String code,
            @Schema(nullable = true, description = "Vorhandenes Dokument; fehlt, wenn es nicht sichtbar ist")
            UUID vorhandenId) {

        static WarnungDto aus(Warnung w) {
            return new WarnungDto(w.code(), w.vorhandenId());
        }
    }

    @Schema(name = "Bestaetigung", description = "Bestätigte Metadaten (A7, Req 8.2–8.5, 8.8, 8.9). Ersetzt die "
            + "Grundfelder sowie die Felder der Gruppen finanz und vertrag vollständig; fehlende Felder sind leer. "
            + "Zusatzfelder kommen aus dem gespeicherten Dokument.")
    public record BestaetigungDto(
            @Schema(required = true, description = "Code einer aktiven Dokumentart") String art,
            @Schema(nullable = true) String titel,
            @Schema(nullable = true) LocalDate datum,
            @Schema(nullable = true, description = "ID eines Kontakts des Mandanten") UUID partnerId,
            @Schema(nullable = true) String projekt,
            @Schema(nullable = true) List<String> tags,
            @Schema(nullable = true, enumeration = {"NORMAL", "INTERN", "PERSONAL"},
                    description = "Fehlt sie, gilt die Standardvertraulichkeit der Art") String vertraulichkeit,
            @Schema(nullable = true) String personBenutzer,
            @Schema(nullable = true, description = "Fehlt es, schlägt das Archiv das Geschäftsjahr vor")
            Integer geschaeftsjahr,
            @Schema(nullable = true) String belegnummer,
            @Schema(nullable = true, enumeration = {"EINNAHME", "AUSGABE"}) String richtung,
            @Schema(nullable = true) BigDecimal betrag,
            @Schema(nullable = true, description = "ISO 4217, Standard CHF") String waehrung,
            @Schema(nullable = true) LocalDate faelligkeit,
            @Schema(nullable = true) String qrReferenz,
            @Schema(nullable = true) String konto,
            @Schema(nullable = true) LocalDate gueltigAb,
            @Schema(nullable = true) LocalDate gueltigBis,
            @Schema(nullable = true) String kuendigungsfrist,
            @Schema(nullable = true) LocalDate naechsteFrist,
            @Schema(description = "Ohne PDF/A bestätigen, wenn die PDF/A-Fassung fehlt (Req 5.9)") boolean ohnePdfa,
            @Schema(description = "Ablauf der Aufbewahrungsfrist ausdrücklich bestätigen (Req 9.8)")
            boolean fristAbgelaufenAkzeptiert) {

        Bestaetigung bestaetigung() {
            return new Bestaetigung(art, titel, datum, partnerId, projekt, tags, vertraulichkeit, personBenutzer,
                    geschaeftsjahr, belegnummer, richtung, betrag, waehrung, faelligkeit, qrReferenz, konto, gueltigAb,
                    gueltigBis, kuendigungsfrist, naechsteFrist, ohnePdfa, fristAbgelaufenAkzeptiert);
        }
    }

    @Schema(name = "DokumentAenderung", description = "Änderung der Metadaten (A6, Aufgabe 3.5). Nur die "
            + "angegebenen Felder ändern sich; null oder ein leerer Text leert ein Feld (tags: null → keine Tags). "
            + "Unbekannte Felder → 400. Finanz- und Vertragsfelder nur, wenn die Art die Gruppe hat.")
    public record DokumentAenderungDto(
            @Schema(nullable = true, description = "Code einer aktiven Dokumentart; bei ARCHIVIERT nicht leer")
            String art,
            @Schema(nullable = true) String titel,
            @Schema(nullable = true) LocalDate datum,
            @Schema(nullable = true, description = "ID eines Kontakts des Mandanten") UUID partnerId,
            @Schema(nullable = true) String projekt,
            @Schema(nullable = true) List<String> tags,
            @Schema(enumeration = {"NORMAL", "INTERN", "PERSONAL"}, description = "Nur mit Recht A8 (sonst 403 für "
                    + "die ganze Änderung); nicht leer") String vertraulichkeit,
            @Schema(nullable = true, description = "Nur mit Recht A8 (sonst 403 für die ganze Änderung)")
            String personBenutzer,
            @Schema(nullable = true) Integer geschaeftsjahr,
            @Schema(nullable = true) String belegnummer,
            @Schema(nullable = true, enumeration = {"EINNAHME", "AUSGABE"}) String richtung,
            @Schema(nullable = true) BigDecimal betrag,
            @Schema(description = "ISO 4217; nicht leer") String waehrung,
            @Schema(nullable = true) LocalDate faelligkeit,
            @Schema(nullable = true) String qrReferenz,
            @Schema(nullable = true) String konto,
            @Schema(nullable = true) LocalDate gueltigAb,
            @Schema(nullable = true) LocalDate gueltigBis,
            @Schema(nullable = true) String kuendigungsfrist,
            @Schema(nullable = true) LocalDate naechsteFrist,
            @Schema(nullable = true, type = SchemaType.OBJECT, description = "Zusatzfelder nach dem Schema der Art; "
                    + "ersetzt die bisherigen vollständig") JsonNode zusatz,
            @Schema(nullable = true, maxLength = DokumentAendern.GRUND_MAX,
                    description = "Grund der Änderung; bei ARCHIVIERT Pflicht (Req 10.1)") String grund) {
    }

    @Schema(name = "Verwerfen", description = "Grund des Verwerfens (Req 6.6)")
    public record VerwerfenDto(@Schema(required = true, maxLength = 1000) String grund) {
    }

    @Schema(name = "DokumentFinanz", description = "Gespeicherte Finanzangaben; die Bestätigung ersetzt sie ganz")
    public record FinanzDto(
            @Schema(nullable = true) String belegnummer,
            @Schema(nullable = true, enumeration = {"EINNAHME", "AUSGABE"}) String richtung,
            @Schema(nullable = true) BigDecimal betrag,
            @Schema(required = true, description = "ISO 4217") String waehrung,
            @Schema(nullable = true) LocalDate faelligkeit,
            @Schema(nullable = true) String qrReferenz,
            @Schema(nullable = true) String konto) {

        static FinanzDto aus(DokumentFinanz f) {
            return f == null ? null : new FinanzDto(f.belegnummer(), f.richtung(), f.betrag(), f.waehrung(),
                    f.faelligkeit(), f.qrReferenz(), f.konto());
        }
    }

    @Schema(name = "DokumentVertrag", description = "Gespeicherte Vertragsangaben; die Bestätigung ersetzt sie ganz")
    public record VertragDto(
            @Schema(nullable = true) LocalDate gueltigAb,
            @Schema(nullable = true) LocalDate gueltigBis,
            @Schema(nullable = true) String kuendigungsfrist,
            @Schema(nullable = true) LocalDate naechsteFrist) {

        static VertragDto aus(DokumentVertrag v) {
            return v == null ? null : new VertragDto(v.gueltigAb(), v.gueltigBis(), v.kuendigungsfrist(),
                    v.naechsteFrist());
        }
    }

    @Schema(name = "Dokument", description = "Metadaten eines sichtbaren Dokuments")
    public record DokumentDto(
            @Schema(required = true) UUID id,
            @Schema(nullable = true, description = "Code der Dokumentart; null, solange nicht erkannt") String art,
            @Schema(nullable = true) String titel,
            @Schema(nullable = true) LocalDate datum,
            @Schema(required = true) Instant eingang,
            @Schema(required = true, enumeration = {"SCAN", "UPLOAD", "GENERATOR", "DRIVE", "EMAIL", "ABANINJA"})
            String quelle,
            @Schema(required = true) List<String> tags,
            @Schema(required = true, enumeration = {"NORMAL", "INTERN", "PERSONAL"},
                    description = "Ohne bestätigte Art gilt PERSONAL (Req 2.21)") String vertraulichkeit,
            @Schema(required = true, enumeration = {"NEU", "VERARBEITUNG", "EINGANGSKORB", "ARCHIVIERUNG",
                    "ARCHIVIERUNG_FEHLER", "ARCHIVIERT", "FEHLER", "VERWORFEN"}) String status,
            @Schema(nullable = true) Instant bestaetigt,
            @Schema(nullable = true) Integer geschaeftsjahr,
            @Schema(nullable = true) LocalDate aufbewahrenBis,
            @Schema(nullable = true) LocalDate sperreBis,
            @Schema(required = true) boolean fristAbgelaufen,
            @Schema(nullable = true, type = SchemaType.OBJECT, description = "Zusatzfelder nach dem Schema der Art")
            JsonNode zusatz,
            @Schema(required = true, description = "Version für If-Match (auch als ETag)") int version,
            @Schema(required = true, description = "Nur im Eingangskorb, sonst leer (Req 8.10)")
            List<WarnungDto> warnungen,
            @Schema(nullable = true, description = "Grund des Fehlers bei FEHLER oder ARCHIVIERUNG_FEHLER (Req 8.7)")
            String fehler,
            @Schema(required = true, description = "PDF/A-Fassung fehlt (Req 5.9)") boolean pdfaFehlt,
            @Schema(nullable = true, description = "Seitenzahl; Vorschau je Seite unter /vorschau") Integer seiten,
            @Schema(nullable = true) UUID partnerId,
            @Schema(nullable = true) String partnerName,
            @Schema(nullable = true, type = SchemaType.OBJECT,
                    description = "Metadaten-Vorschlag der KI; nur im Eingangskorb, sonst null (Req 8.1)")
            JsonNode vorschlag,
            @Schema(required = true, description = "Felder des Vorschlags unter der Schwelle archiv.ki.schwelle "
                    + "(«bitte prüfen»); nur im Eingangskorb, sonst leer (Req 7.6)")
            List<String> unsicher,
            @Schema(nullable = true) String projekt,
            @Schema(nullable = true, description = "Zugeordnete Person (Benutzer-ID) eines PERSONAL-Dokuments")
            String personBenutzer,
            @Schema(nullable = true) FinanzDto finanz,
            @Schema(nullable = true) VertragDto vertrag) {
    }

    @GET
    @Path("/{id}")
    @ErfordertAktion(Aktion.A2)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.ANZEIGEN)
    @Operation(summary = "Metadaten eines Dokuments lesen",
            description = "Unsichtbare und fremde Dokumente → 404 NICHT_GEFUNDEN (Req 1.4, 2.3).")
    @APIResponse(responseCode = "200", description = "Dokument",
            headers = @Header(name = "ETag", description = "Version des Dokuments für If-Match"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = DokumentDto.class)))
    public Response dokument(@PathParam("id") UUID id) {
        aufloesung.pruefen();
        Benutzer benutzer = benutzer(identitaet);
        Dokument dokument = lesen.lesen(benutzer, id);
        return Response.ok(dto(dokument, json, lesen.warnungen(benutzer, dokument), lesen.schwelle()))
                .tag(new EntityTag(Integer.toString(dokument.version()))).build();
    }

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A6)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.AENDERN)
    @Operation(summary = "Metadaten eines Dokuments ändern",
            description = "Nur in EINGANGSKORB, FEHLER und ARCHIVIERT (sonst 409 STATUS_KONFLIKT). Vertraulichkeit "
                    + "oder personBenutzer ohne Recht A8 → 403 für die ganze Änderung. Bei ARCHIVIERT ist grund "
                    + "Pflicht (422 PFLICHTFELD_FEHLT), die vorherige Fassung geht nach dokument_version, kein "
                    + "Pflichtfeld der Art darf neu fehlen, und eine Änderung von datum, art, geschaeftsjahr oder "
                    + "gueltigBis berechnet die Aufbewahrungsfrist neu: Frist und Sperre werden nur verlängert, ein "
                    + "früheres Ergebnis bei gesperrten Dokumenten als SPERRE_KONFLIKT protokolliert (Req 9.7, 10.1). "
                    + "Ungültige Zusatzfelder → 422 ZUSATZ_UNGUELTIG; Partner unbekannt → 422 PARTNER_UNBEKANNT; "
                    + "Belegnummer einer eigenen Art schon vergeben → 409 BELEGNUMMER_VERGEBEN.")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = DokumentAenderungDto.class)))
    @APIResponse(responseCode = "200", description = "Geändertes Dokument",
            headers = @Header(name = "ETag", description = "Neue Version des Dokuments"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = DokumentDto.class)))
    @APIResponse(responseCode = "412", description = "If-Match passt nicht zur aktuellen Version (VERSION_KONFLIKT)")
    @APIResponse(responseCode = "428", description = "If-Match fehlt (VERSION_FEHLT)")
    public Response aendern(@PathParam("id") UUID id,
                            @HeaderParam(HttpHeaders.IF_MATCH) @Parameter(required = true) String ifMatch,
                            String inhalt) {
        aufloesung.pruefen();
        Benutzer benutzer = benutzer(identitaet);
        if (betrifftA8(inhalt)) {
            berechtigung.dokumentAktion(benutzer, Aktion.A8, id);
        }
        int version = version(ifMatch);
        aendern.aendern(benutzer, id, version, () -> aenderung(knoten(inhalt)));
        Dokument dokument = lesen.lesen(benutzer, id);
        return Response.ok(dto(dokument, json, lesen.warnungen(benutzer, dokument), lesen.schwelle()))
                .tag(new EntityTag(Integer.toString(dokument.version()))).build();
    }

    @POST
    @Path("/{id}/bestaetigen")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A7)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.BESTAETIGEN)
    @Operation(summary = "Dokument bestätigen und archivieren",
            description = "Nur aus EINGANGSKORB (sonst 409 STATUS_KONFLIKT). Pflichtfelder der Art fehlen → 422 "
                    + "PFLICHTFELD_FEHLT mit felder; Partner unbekannt → 422 PARTNER_UNBEKANNT; Belegnummer einer "
                    + "eigenen Art schon vergeben → 409 BELEGNUMMER_VERGEBEN; Aufbewahrungsfrist abgelaufen ohne "
                    + "fristAbgelaufenAkzeptiert → 409 FRIST_ABGELAUFEN. Danach ARCHIVIERUNG und Auftrag ARCHIVIEREN.")
    @APIResponse(responseCode = "202", description = "Bestätigt, Archivierung eingereiht",
            headers = @Header(name = "ETag", description = "Neue Version des Dokuments"))
    @APIResponse(responseCode = "412", description = "If-Match passt nicht zur aktuellen Version (VERSION_KONFLIKT)")
    @APIResponse(responseCode = "428", description = "If-Match fehlt (VERSION_FEHLT)")
    public Response bestaetigen(@PathParam("id") UUID id,
                                @HeaderParam(HttpHeaders.IF_MATCH) @Parameter(required = true) String ifMatch,
                                BestaetigungDto angaben) {
        aufloesung.pruefen();
        int version = version(ifMatch);
        if (angaben == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        int neu = bestaetigen.bestaetigen(benutzer(identitaet), id, version, angaben.bestaetigung());
        return Response.accepted().tag(new EntityTag(Integer.toString(neu))).build();
    }

    @POST
    @Path("/{id}/verwerfen")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A9)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.VERWERFEN)
    @Operation(summary = "Dokument verwerfen",
            description = "Nur aus EINGANGSKORB und FEHLER (sonst 409 STATUS_KONFLIKT, nichts wird gelöscht). Löscht "
                    + "alle Dateien unter eingang/ und protokolliert je Datei SHA-256 und Grund (Req 6.6, 6.7).")
    @APIResponse(responseCode = "204", description = "Verworfen")
    @APIResponse(responseCode = "412", description = "If-Match passt nicht zur aktuellen Version (VERSION_KONFLIKT)")
    @APIResponse(responseCode = "428", description = "If-Match fehlt (VERSION_FEHLT)")
    public Response verwerfen(@PathParam("id") UUID id,
                              @HeaderParam(HttpHeaders.IF_MATCH) @Parameter(required = true) String ifMatch,
                              VerwerfenDto angaben) {
        aufloesung.pruefen();
        int version = version(ifMatch);
        verwerfen.verwerfen(benutzer(identitaet), id, version, angaben == null ? null : angaben.grund());
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/wiederholen")
    @ErfordertAktion(Aktion.A10)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.WIEDERHOLEN)
    @Operation(summary = "Verarbeitung oder Archivierung wiederholen",
            description = "FEHLER → NEU (neue Verarbeitung), ARCHIVIERUNG_FEHLER → ARCHIVIERUNG (neue Archivierung, "
                    + "Metadaten unverändert); sonst 409 STATUS_KONFLIKT (Req 6.8, 6.13).")
    @APIResponse(responseCode = "202", description = "Neuer Auftrag eingereiht",
            headers = @Header(name = "ETag", description = "Neue Version des Dokuments"))
    @APIResponse(responseCode = "412", description = "If-Match passt nicht zur aktuellen Version (VERSION_KONFLIKT)")
    @APIResponse(responseCode = "428", description = "If-Match fehlt (VERSION_FEHLT)")
    public Response wiederholen(@PathParam("id") UUID id,
                                @HeaderParam(HttpHeaders.IF_MATCH) @Parameter(required = true) String ifMatch) {
        aufloesung.pruefen();
        int neu = wiederholen.wiederholen(benutzer(identitaet), id, version(ifMatch));
        return Response.accepted().tag(new EntityTag(Integer.toString(neu))).build();
    }

    /**
     * Fehlt If-Match → 428; alles, was kein starkes ETag {@code "<version>"} ist (auch {@code *}, {@code W/"…"} oder
     * eine Zahl ohne Anführungszeichen), passt nie → 412.
     */
    static int version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new FachFehler(Fehlercode.VERSION_FEHLT);
        }
        Matcher treffer = VERSION.matcher(ifMatch.strip());
        if (!treffer.matches()) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        return Integer.parseInt(treffer.group(1));
    }

    @GET
    @Path("/{id}/datei")
    @ErfordertAktion(Aktion.A2)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.HERUNTERLADEN)
    @Operation(summary = "Datei eines Dokuments herunterladen",
            description = "Antwortet mit 302 auf eine vorsignierte URL für genau die gespeicherte Version, höchstens "
                    + "5 Minuten gültig (Req 14.6). variante pdfa (Standard, ohne nr), original oder arbeitsfassung "
                    + "(nr ab 1, Standard 1). Unsichtbare, verworfene oder fehlende Dateien → 404 NICHT_GEFUNDEN.")
    @APIResponse(responseCode = "302", description = "Weiterleitung auf die vorsignierte URL",
            headers = @Header(name = "Location", description = "vorsignierte URL, nur für GET"))
    public Response datei(@PathParam("id") UUID id,
                          @QueryParam("variante") @Parameter(schema = @Schema(type = SchemaType.STRING,
                                  enumeration = {"pdfa", "original", "arbeitsfassung"}, defaultValue = "pdfa"))
                          String variante,
                          @QueryParam("nr") @Parameter(description = "Nummer der Datei (nur original und "
                                  + "arbeitsfassung)", schema = @Schema(type = SchemaType.INTEGER, format = "int32",
                                  minimum = "1", maximum = "9999")) String nr) {
        MandantCode mandant = aufloesung.pruefen();
        DateiRolle rolle = rolle(variante);
        int nummer = nummer(rolle, nr);
        VorsignierteUrl url = datei.herunterladen(benutzer(identitaet), mandant, id, rolle, nummer);
        return Response.status(Response.Status.FOUND).location(url.url())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static DateiRolle rolle(String variante) {
        return switch (variante == null || variante.isEmpty() ? "pdfa" : variante) {
            case "pdfa" -> DateiRolle.PDFA;
            case "original" -> DateiRolle.ORIGINAL;
            case "arbeitsfassung" -> DateiRolle.ARBEITSFASSUNG;
            default -> throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        };
    }

    private static int nummer(DateiRolle rolle, String nr) {
        boolean ohne = nr == null || nr.isEmpty();
        if (rolle == DateiRolle.PDFA) {
            if (!ohne) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            return 0;
        }
        if (ohne) {
            return 1;
        }
        try {
            int nummer = Integer.parseInt(nr);
            if (nummer >= 1 && nummer <= 9999) {
                return nummer;
            }
        } catch (NumberFormatException e) {
            // unten als ungültig gemeldet
        }
        throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }

    /** Beträge als BigDecimal, nie über double (Regel 9). */
    private ObjectReader leser() {
        return json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Ein JSON-Objekt mit mindestens einem bekannten Feld; sonst 400. */
    /**
     * A8 gehört als Recht vor {@code If-Match} (design.md «Berechtigung», 403 vor 428/412). Ein unlesbarer Körper
     * braucht kein A8; er scheitert erst im Anwendungsfall nach 404, 428/412 und 409 mit 400 (Review PR #67, S1).
     */
    private boolean betrifftA8(String inhalt) {
        if (inhalt == null || inhalt.isBlank()) {
            return false;
        }
        try {
            JsonNode k = leser().readTree(inhalt);
            return k != null && k.isObject() && Aenderung.NUR_A8.stream().anyMatch(k::has);
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private JsonNode knoten(String inhalt) {
        JsonNode knoten;
        try {
            knoten = inhalt == null || inhalt.isBlank() ? null : leser().readTree(inhalt);
        } catch (JsonProcessingException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if (knoten == null || !knoten.isObject() || knoten.isEmpty()) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        for (var namen = knoten.fieldNames(); namen.hasNext(); ) {
            String name = namen.next();
            if (!Aenderung.FELDER.contains(name) && !GRUND.equals(name)) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
        }
        return knoten;
    }

    private Aenderung aenderung(JsonNode knoten) {
        DokumentAenderungDto d;
        try {
            d = leser().treeToValue(knoten, DokumentAenderungDto.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Set<String> gesetzt = new HashSet<>();
        knoten.fieldNames().forEachRemaining(gesetzt::add);
        gesetzt.remove(GRUND);
        JsonNode zusatz = d.zusatz() == null || d.zusatz().isNull() ? null : d.zusatz();
        if (zusatz != null && !zusatz.isObject()) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Set<String> zusatzFelder = new HashSet<>();
        if (zusatz != null) {
            zusatz.fields().forEachRemaining(e -> {
                JsonNode w = e.getValue();
                if (!(w.isNull() || w.isTextual() && w.asText().isEmpty() || w.isContainerNode() && w.isEmpty())) {
                    zusatzFelder.add(e.getKey());
                }
            });
        }
        String grund = d.grund();
        if (grund != null && grund.length() > DokumentAendern.GRUND_MAX) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return new Aenderung(gesetzt, text(d.art()), text(d.titel()), d.datum(), d.partnerId(), text(d.projekt()),
                d.tags(), text(d.vertraulichkeit()), text(d.personBenutzer()), d.geschaeftsjahr(),
                text(d.belegnummer()), text(d.richtung()), d.betrag(), text(d.waehrung()), d.faelligkeit(),
                text(d.qrReferenz()), text(d.konto()), d.gueltigAb(), d.gueltigBis(), text(d.kuendigungsfrist()),
                d.naechsteFrist(), zusatz == null ? null : zusatz.toString(), zusatzFelder, grund);
    }

    /** Ein leerer Text leert das Feld wie {@code null}. */
    private static String text(String wert) {
        return wert == null || wert.isBlank() ? null : wert;
    }

    /** Der ZugangFilter hat Rolle und Benutzer-ID schon geprüft (sonst 403). */
    static Benutzer benutzer(SecurityIdentity identitaet) {
        return new Benutzer(identitaet.getPrincipal().getName(),
                Rolle.ausRealmRollen(identitaet.getRoles()).orElseThrow());
    }

    static DokumentDto dto(Dokument d, ObjectMapper json, List<Warnung> warnungen, BigDecimal schwelle) {
        boolean eingangskorb = DokumentStatus.EINGANGSKORB_STATUS.contains(d.status());
        JsonNode vorschlag = eingangskorb ? lesenJson(d, d.kiVorschlag(), "ki_vorschlag", json) : null;
        List<String> unsicher = eingangskorb
                ? unsicher(vorschlag, lesenJson(d, d.kiSicherheit(), "ki_sicherheit", json), schwelle)
                : List.of();
        return new DokumentDto(d.id(), d.art(), d.titel(), d.datum(), d.eingang(), d.quelle(), d.tags(),
                d.wirksameVertraulichkeit(), d.status().name(), d.bestaetigt(), d.geschaeftsjahr(), d.aufbewahrenBis(),
                d.sperreBis(), d.fristAbgelaufen(), lesenJson(d, d.zusatz(), "zusatz", json), d.version(),
                warnungen.stream().map(WarnungDto::aus).toList(), d.fehler(), d.pdfaFehlt(), d.seiten(),
                d.partnerId(), d.partnerName(), vorschlag, unsicher, d.projekt(), d.personBenutzer(),
                FinanzDto.aus(d.finanz()), VertragDto.aus(d.vertrag()));
    }

    // Vorgeschlagen ist jedes Feld mit Wert; die Sicherheit steht in ki_sicherheit, sonst im Vorschlag selbst
    private static List<String> unsicher(JsonNode vorschlag, JsonNode sicherheit, BigDecimal schwelle) {
        if (vorschlag == null && sicherheit == null) {
            return List.of();
        }
        Set<String> vorgeschlagen = new HashSet<>();
        if (vorschlag != null && vorschlag.isObject()) {
            vorschlag.properties().forEach(feld -> {
                if (!feld.getKey().equals(SICHERHEIT) && !feld.getValue().isNull()) {
                    vorgeschlagen.add(feld.getKey());
                }
            });
        }
        JsonNode quelle = sicherheit != null ? sicherheit : vorschlag == null ? null : vorschlag.get(SICHERHEIT);
        Map<String, BigDecimal> werte = new HashMap<>();
        if (quelle != null && quelle.isObject()) {
            quelle.properties().forEach(feld -> werte.put(feld.getKey(),
                    feld.getValue().isNumber() ? feld.getValue().decimalValue() : null));
        }
        return KiSicherheit.unsicher(vorgeschlagen, werte, schwelle);
    }

    private static JsonNode lesenJson(Dokument d, String wert, String spalte, ObjectMapper json) {
        if (wert == null) {
            return null;
        }
        try {
            return json.readTree(wert);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(spalte + " des Dokuments " + d.id() + " ist kein JSON", e);
        }
    }
}
