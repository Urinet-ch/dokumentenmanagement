package ch.urinet.archiv.importe.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.application.quelle.QuellenVerwaltung;
import ch.urinet.archiv.importe.domain.quelle.QuellLauf;
import ch.urinet.archiv.importe.domain.quelle.Quelle;
import ch.urinet.archiv.importe.domain.quelle.Zaehler;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.ErlaubtScope;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
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
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quellenregister und Quellläufe (Spec 09, Requirement 10, design.md Abschnitt 5): ansehen mit A27, pflegen mit A28.
 * Start und Abschluss eines Laufs erreicht auch ein API-Token vom Scope {@code import} (10.9); Änderungen am Register
 * stehen als {@code KONFIGURATION_AENDERN} im Zugriffsprotokoll.
 */
@Path("/quellen")
@Tag(name = "Quellen")
@Produces(MediaType.APPLICATION_JSON)
public class QuellenResource {

    private static final Pattern VERSION = Pattern.compile("^\"(\\d{1,9})\"$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final QuellenVerwaltung verwaltung;
    private final SecurityIdentity identitaet;

    public QuellenResource(QuellenVerwaltung verwaltung, SecurityIdentity identitaet) {
        this.verwaltung = verwaltung;
        this.identitaet = identitaet;
    }

    @Schema(name = "Quellzaehler", description = "Zähler eines Quelllaufs (Spec 09, 10.3); gefunden = neu + "
            + "duplettePruefsumme + uebersprungen + fehler")
    public record ZaehlerDto(@Schema(required = true) int gefunden, @Schema(required = true) int neu,
                             @Schema(required = true) int duplettePruefsumme,
                             @Schema(required = true, description = "im Trockenlauf immer 0") int dubletteFachlich,
                             @Schema(required = true) int uebersprungen, @Schema(required = true) int fehler) {

        static ZaehlerDto aus(Zaehler z) {
            return new ZaehlerDto(z.gefunden(), z.neu(), z.dublettePruefsumme(), z.dubletteFachlich(),
                    z.uebersprungen(), z.fehler());
        }
    }

    @Schema(name = "Quelllauf")
    public record LaufDto(@Schema(required = true) UUID id, @Schema(required = true) String quelle,
                          @Schema(required = true, enumeration = {"TROCKEN", "ECHT", "AUTOMATISCH"}) String art,
                          @Schema(required = true, enumeration = {"LAEUFT", "FERTIG", "ABGEBROCHEN"}) String status,
                          @Schema(required = true) ZaehlerDto zaehler, @Schema(required = true) List<String> meldungen,
                          @Schema(nullable = true) String benutzer, @Schema(required = true) Instant gestartet,
                          @Schema(nullable = true) Instant beendet) {

        static LaufDto aus(QuellLauf l) {
            return new LaufDto(l.id(), l.quelleCode(), l.art().name(), l.status().name(), ZaehlerDto.aus(l.zaehler()),
                    l.meldungen(), l.benutzer().orElse(null), l.gestartet(), l.beendet().orElse(null));
        }
    }

    @Schema(name = "Registerquelle", description = "Eintrag im Quellenregister (Spec 09, 10.1); das Geheimnis wird nie "
            + "zurückgegeben")
    public record QuelleDto(@Schema(required = true) String code, @Schema(required = true) String name,
                            @Schema(required = true, enumeration = {"GENERATOR", "DRIVE", "ORDNER", "MAC_EINGANG",
                                    "GMAIL_LABEL", "CRM_CSV", "SCAN"}) String typ,
                            @Schema(required = true, type = SchemaType.OBJECT) JsonNode konfiguration,
                            @Schema(required = true, enumeration = {"OFFEN", "LAEUFT", "ABGESCHLOSSEN", "LAUFEND"})
                            String status,
                            @Schema(nullable = true) String bemerkung,
                            @Schema(required = true) boolean geheimnisGesetzt,
                            @Schema(required = true, description = "Version für If-Match") int version,
                            @Schema(nullable = true) LaufDto letzterLauf) {

        static QuelleDto aus(Quelle q) {
            return new QuelleDto(q.code(), q.name(), q.typ().name(), json(q.konfiguration()), q.status().name(),
                    q.bemerkung().orElse(null), q.geheimnisGesetzt(), q.version(),
                    q.letzterLauf().map(LaufDto::aus).orElse(null));
        }
    }

    @Schema(name = "QuelleAnlegen")
    public record AnlegenDto(@Schema(required = true, pattern = "^[a-z0-9][a-z0-9-]{0,39}$") String code,
                             @Schema(required = true, maxLength = Quelle.NAME_MAX) String name,
                             @Schema(required = true, enumeration = {"GENERATOR", "DRIVE", "ORDNER", "MAC_EINGANG",
                                     "GMAIL_LABEL", "CRM_CSV", "SCAN"}) String typ,
                             @Schema(type = SchemaType.OBJECT) JsonNode konfiguration,
                             @Schema(maxLength = Quelle.BEMERKUNG_MAX) String bemerkung) {
    }

    @Schema(name = "QuelleAendern", description = "Fehlende Felder bleiben; eine leere Bemerkung löscht sie")
    public record AendernDto(@Schema(enumeration = {"OFFEN", "LAEUFT", "ABGESCHLOSSEN", "LAUFEND"}) String status,
                             @Schema(maxLength = Quelle.BEMERKUNG_MAX) String bemerkung,
                             @Schema(type = SchemaType.OBJECT) JsonNode konfiguration) {
    }

    @Schema(name = "QuelleGeheimnis")
    public record GeheimnisDto(@Schema(required = true, description = "z. B. App-Passwort; nur verschlüsselt "
            + "gespeichert") String geheimnis) {
    }

    @Schema(name = "QuelllaufStarten")
    public record StartDto(@Schema(required = true, enumeration = {"TROCKEN", "ECHT"}) String art) {
    }

    @Schema(name = "QuelllaufAbschliessen", description = "Trockenlauf: alle Zähler vom Werkzeug; echter Lauf: "
            + "der Server zählt, das Werkzeug meldet nur übersprungene Dateien")
    public record AbschlussDto(@Schema(nullable = true) ZaehlerDto zaehler,
                               @Schema(nullable = true) Integer uebersprungen,
                               @Schema(nullable = true) List<String> meldungen,
                               @Schema(nullable = true, description = "true: Lauf ABGEBROCHEN") Boolean abgebrochen) {
    }

    @GET
    @ErfordertAktion(Aktion.A27)
    @RolesAllowed({"inhaber", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Quellenregister", description = "Alle Quellen des Mandanten mit letztem Lauf (A27)")
    @APIResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(type = SchemaType.ARRAY, implementation = QuelleDto.class)))
    public List<QuelleDto> liste() {
        return verwaltung.liste().stream().map(QuelleDto::aus).toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A28)
    @RolesAllowed("inhaber")
    @Protokolliert(aktion = ProtokollAktion.KONFIGURATION_AENDERN)
    @Operation(summary = "Quelle anlegen", description = "Neue Quelle im Register, Status OFFEN (A28)")
    @APIResponse(responseCode = "201", description = "Angelegt",
            headers = @Header(name = "ETag", description = "Version für If-Match"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = QuelleDto.class)))
    @APIResponse(responseCode = "400", description = "ANFRAGE_UNGUELTIG")
    @APIResponse(responseCode = "409", description = "QUELLE_EXISTIERT: Code schon vergeben")
    public Response anlegen(AnlegenDto neu) {
        if (neu == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Quelle quelle = verwaltung.anlegen(neu.code(), neu.name(), neu.typ(), objekt(neu.konfiguration()),
                neu.bemerkung());
        return Response.status(Response.Status.CREATED).entity(QuelleDto.aus(quelle))
                .tag(new EntityTag(Integer.toString(quelle.version()))).build();
    }

    @PATCH
    @Path("/{code}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A28)
    @RolesAllowed("inhaber")
    @Protokolliert(aktion = ProtokollAktion.KONFIGURATION_AENDERN)
    @Operation(summary = "Quelle ändern", description = "Status, Bemerkung, Konfiguration; optimistisches Locking "
            + "über If-Match (A28)")
    @APIResponse(responseCode = "200", headers = @Header(name = "ETag", description = "Neue Version"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = QuelleDto.class)))
    @APIResponse(responseCode = "400", description = "ANFRAGE_UNGUELTIG")
    @APIResponse(responseCode = "404", description = "NICHT_GEFUNDEN")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response aendern(@PathParam("code") String code,
                            @HeaderParam(HttpHeaders.IF_MATCH) @Parameter(required = true) String ifMatch,
                            AendernDto aenderung) {
        int version = version(ifMatch);
        if (aenderung == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Quelle quelle = verwaltung.aendern(code, version, aenderung.status(), aenderung.bemerkung(),
                aenderung.konfiguration() == null ? null : objekt(aenderung.konfiguration()));
        return Response.ok(QuelleDto.aus(quelle)).tag(new EntityTag(Integer.toString(quelle.version()))).build();
    }

    @PUT
    @Path("/{code}/geheimnis")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A28)
    @RolesAllowed("inhaber")
    @Protokolliert(aktion = ProtokollAktion.KONFIGURATION_AENDERN)
    @Operation(summary = "Geheimnis einer Quelle setzen", description = "Nur verschlüsselt gespeichert, nie "
            + "zurückgegeben (A28)")
    @APIResponse(responseCode = "204", description = "Gesetzt")
    @APIResponse(responseCode = "400", description = "ANFRAGE_UNGUELTIG")
    @APIResponse(responseCode = "404", description = "NICHT_GEFUNDEN")
    @APIResponse(responseCode = "409", description = "STATUS_KONFLIKT: Datenschlüssel fehlt")
    public Response geheimnis(@PathParam("code") String code, GeheimnisDto geheimnis) {
        verwaltung.geheimnisSetzen(code, geheimnis == null ? null : geheimnis.geheimnis());
        return Response.noContent().build();
    }

    @POST
    @Path("/{code}/laeufe")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A28)
    @ErlaubtScope(ApiTokenScope.IMPORT)
    @RolesAllowed({"inhaber", ApiTokenScope.ROLLE_IMPORT})
    @Protokolliert(aktion = ProtokollAktion.KONFIGURATION_AENDERN)
    @Operation(summary = "Quelllauf starten", description = "Trockenlauf oder echter Lauf; ein laufender "
            + "automatischer Lauf endet dabei (A28, Scope import)")
    @APIResponse(responseCode = "201", description = "Lauf gestartet",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = LaufDto.class)))
    @APIResponse(responseCode = "400", description = "ANFRAGE_UNGUELTIG")
    @APIResponse(responseCode = "404", description = "NICHT_GEFUNDEN")
    @APIResponse(responseCode = "409", description = "QUELLE_LAEUFT: ein Trocken- oder echter Lauf läuft schon")
    public Response laufStarten(@PathParam("code") String code, StartDto start) {
        QuellLauf lauf = verwaltung.laufStarten(code, start == null ? null : start.art(), benutzer());
        return Response.status(Response.Status.CREATED).entity(LaufDto.aus(lauf)).build();
    }

    @POST
    @Path("/laeufe/{laufId}/abschliessen")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A28)
    @ErlaubtScope(ApiTokenScope.IMPORT)
    @RolesAllowed({"inhaber", ApiTokenScope.ROLLE_IMPORT})
    @Protokolliert(aktion = ProtokollAktion.KONFIGURATION_AENDERN)
    @Operation(summary = "Quelllauf abschliessen", description = "Zähler und Meldungen des Werkzeugs; ein "
            + "Trockenlauf legt keine Dokumente an (A28, Scope import)")
    @APIResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = LaufDto.class)))
    @APIResponse(responseCode = "400", description = "ANFRAGE_UNGUELTIG: Zähler gehen nicht auf oder passen nicht "
            + "zur Art des Laufs")
    @APIResponse(responseCode = "404", description = "NICHT_GEFUNDEN")
    @APIResponse(responseCode = "409", description = "STATUS_KONFLIKT: Lauf schon beendet")
    public LaufDto laufAbschliessen(@PathParam("laufId") String laufId, AbschlussDto abschluss) {
        AbschlussDto a = abschluss == null ? new AbschlussDto(null, null, null, null) : abschluss;
        Optional<Zaehler> zaehler;
        try {
            zaehler = Optional.ofNullable(a.zaehler()).map(z -> new Zaehler(z.gefunden(), z.neu(),
                    z.duplettePruefsumme(), z.dubletteFachlich(), z.uebersprungen(), z.fehler()));
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return LaufDto.aus(verwaltung.laufAbschliessen(uuid(laufId), zaehler,
                a.uebersprungen() == null ? 0 : a.uebersprungen(), a.meldungen() == null ? List.of() : a.meldungen(),
                Boolean.TRUE.equals(a.abgebrochen())));
    }

    @GET
    @Path("/{code}/laeufe")
    @ErfordertAktion(Aktion.A27)
    @RolesAllowed({"inhaber", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Quellläufe einer Quelle", description = "Neueste zuerst, höchstens 100 (A27)")
    @APIResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(type = SchemaType.ARRAY, implementation = LaufDto.class)))
    @APIResponse(responseCode = "404", description = "NICHT_GEFUNDEN")
    public List<LaufDto> laeufe(@PathParam("code") String code) {
        return verwaltung.laeufe(code).stream().map(LaufDto::aus).toList();
    }

    private Optional<String> benutzer() {
        return identitaet.isAnonymous() ? Optional.empty() : Optional.ofNullable(identitaet.getPrincipal().getName());
    }

    /** Fehlt If-Match → 428; alles ausser einem starken ETag {@code "<version>"} → 412 (wie bei Kontakten). */
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

    private static UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
    }

    private static String objekt(JsonNode konfiguration) {
        if (konfiguration == null) {
            return null;
        }
        if (!konfiguration.isObject()) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return konfiguration.toString();
    }

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException e) {
            return JSON.createObjectNode();
        }
    }
}
