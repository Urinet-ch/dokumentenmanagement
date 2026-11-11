package ch.urinet.archiv.kontakt.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.kontakt.application.KontaktVerwaltung;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import ch.urinet.archiv.kontakt.domain.KontaktTyp;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
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
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kontakte des eigenen Mandanten (design.md «Kontakt und Importe»): suchen für die Partnerauswahl (A6) und von Hand
 * pflegen (A12, nur {@code inhaber}; Req 2.18). API-Tokens haben keinen Zugriff (kein {@code @ErlaubtScope}). Die
 * Suche protokolliert nicht einzeln (Liste ohne Dokumentbezug, design.md «Protokoll und Manifest»).
 */
@Path("/kontakte")
@Tag(name = "Kontakte")
@Produces(MediaType.APPLICATION_JSON)
public class KontaktResource {

    private static final Pattern VERSION = Pattern.compile("^(?:W/)?\"?(\\d{1,9})\"?$");

    private final KontaktVerwaltung verwaltung;
    private final MandantAufloesung aufloesung;

    public KontaktResource(KontaktVerwaltung verwaltung, MandantAufloesung aufloesung) {
        this.verwaltung = verwaltung;
        this.aufloesung = aufloesung;
    }

    @Schema(name = "KontaktAngaben", description = "Bei PATCH bleibt ein fehlendes Feld unverändert, ein leerer Text "
            + "löscht es; Name und Typ lassen sich nicht leeren.")
    public record KontaktAngabenDto(
            @Schema(enumeration = {"ORGANISATION", "PERSON"}, description = "Pflicht beim Anlegen") String typ,
            @Schema(maxLength = 200, description = "Firmen- bzw. Nachname; Pflicht beim Anlegen") String name,
            @Schema(maxLength = 200) String vorname, @Schema(maxLength = 200) String strasse,
            @Schema(maxLength = 20) String plz, @Schema(maxLength = 200) String ort,
            @Schema(maxLength = 200) String email, @Schema(maxLength = 50) String telefon,
            @Schema(maxLength = 30, description = "UID, z. B. «CHE-123.456.789 MWST»") String uid,
            @Schema(maxLength = 40) String iban, @Schema(maxLength = 100) String kategorie) {

        KontaktAngaben angaben() {
            return new KontaktAngaben(kontaktTyp(typ), name, vorname, strasse, plz, ort, email, telefon, uid, iban, kategorie);
        }
    }

    @Schema(name = "Kontakt")
    public record KontaktDto(String id, @Schema(nullable = true) String crmId,
            @Schema(enumeration = {"ORGANISATION", "PERSON"}) String typ, String name,
            @Schema(nullable = true) String vorname, @Schema(nullable = true) String strasse,
            @Schema(nullable = true) String plz, @Schema(nullable = true) String ort,
            @Schema(nullable = true) String email, @Schema(nullable = true) String telefon,
            @Schema(nullable = true) String uid, @Schema(nullable = true) String iban,
            @Schema(nullable = true) String kategorie, @Schema(description = "MANUELL oder CRM_CSV") String quelle,
            boolean nichtImExport, Instant erstellt, Instant geaendert,
            @Schema(description = "Version für If-Match (auch als ETag)") int version) {

        static KontaktDto aus(Kontakt k) {
            return new KontaktDto(k.id().toString(), k.crmId(), k.typ().name(), k.name(), k.vorname(), k.strasse(),
                    k.plz(), k.ort(), k.email(), k.telefon(), k.uid(), k.iban(), k.kategorie(), k.quelle(),
                    k.nichtImExport(), k.erstellt(), k.geaendert(), k.version());
        }
    }

    @GET
    @ErfordertAktion(Aktion.A6)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Kontakte suchen (Partnerauswahl)",
            description = "Ähnliche oder enthaltene Namen, die besten zuerst; höchstens 20 Treffer.")
    public List<KontaktDto> suchen(@QueryParam("q") @Parameter(description = "Suchtext (Name)") String q) {
        aufloesung.pruefen();
        return verwaltung.suchen(q).stream().map(KontaktDto::aus).toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A12)
    @RolesAllowed("inhaber")
    @Operation(summary = "Kontakt anlegen")
    @APIResponse(responseCode = "201", description = "Angelegt",
            headers = @Header(name = "ETag", description = "Version des Kontakts für If-Match"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = KontaktDto.class)))
    public Response anlegen(KontaktAngabenDto neu) {
        aufloesung.pruefen();
        if (neu == null) {
            throw new FachFehler(Fehlercode.PFLICHTFELD_FEHLT);
        }
        return antwort(Response.status(Response.Status.CREATED), verwaltung.anlegen(neu.angaben()));
    }

    @PATCH
    @Path("/{kontaktId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A12)
    @RolesAllowed("inhaber")
    @Operation(summary = "Kontakt ändern", description = "Optimistisches Locking über If-Match; fremde oder unbekannte "
            + "IDs → 404, fehlendes If-Match → 428, veraltete Version → 412.")
    @APIResponse(responseCode = "200", description = "Geändert",
            headers = @Header(name = "ETag", description = "Neue Version des Kontakts"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = KontaktDto.class)))
    public Response aendern(@PathParam("kontaktId") String kontaktId,
                            @HeaderParam(HttpHeaders.IF_MATCH) @Parameter(required = true) String ifMatch,
                            KontaktAngabenDto aenderung) {
        aufloesung.pruefen();
        UUID id = id(kontaktId);
        int version = version(ifMatch);
        if (aenderung == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return antwort(Response.ok(), verwaltung.aendern(id, version, aenderung.angaben()));
    }

    private static Response antwort(Response.ResponseBuilder builder, Kontakt kontakt) {
        return builder.tag(new EntityTag(Integer.toString(kontakt.version()))).entity(KontaktDto.aus(kontakt)).build();
    }

    private static UUID id(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
    }

    /** Fehlt If-Match → 428; alles, was keine Version ist (auch {@code *}), passt nie → 412. */
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

    private static KontaktTyp kontaktTyp(String text) {
        if (text == null) {
            return null;
        }
        try {
            return KontaktTyp.valueOf(text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }
}
