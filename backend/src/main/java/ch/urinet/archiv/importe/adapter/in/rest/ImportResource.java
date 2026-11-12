package ch.urinet.archiv.importe.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.application.CsvImport;
import ch.urinet.archiv.importe.domain.GespeicherteZuordnung;
import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.importe.domain.Zuordnung;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CSV-Import der CRM-Daten, Erkennung und Spaltenzuordnung (A12, nur {@code inhaber}; Req 2.18, 13.1, 13.2, 13.10,
 * 13.11). Der Mandant kommt nur aus der Anmeldung. Vorschau und Übernahme folgen in den Aufgaben 4.2 und 4.3.
 */
@Path("/importe")
@Tag(name = "Importe")
@Produces(MediaType.APPLICATION_JSON)
public class ImportResource {

    private final CsvImport csvImport;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public ImportResource(CsvImport csvImport, MandantAufloesung aufloesung, SecurityIdentity identitaet) {
        this.csvImport = csvImport;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @Schema(name = "ImportLauf")
    public record LaufDto(UUID id, @Schema(enumeration = {"kontakt", "verkaufschance"}) String art, boolean vollexport,
            @Schema(nullable = true) String dateiname, String sha256,
            @Schema(enumeration = {"UTF-8", "windows-1252"}) String zeichensatz,
            @Schema(enumeration = {";", ","}) String trennzeichen,
            @Schema(description = "Datenzeilen ohne Kopfzeile") int zeilen,
            @Schema(description = "Zeilen «fehlerhaft» (Spaltenzahl, Feldgrenze, Name bzw. Titel fehlt)") int fehler,
            @Schema(description = "Zeilen «Ist gelöscht = true»") int uebersprungen,
            @Schema(enumeration = {"VORSCHAU_AUSSTEHEND", "ZUORDNUNG_NOETIG", "VORSCHAU", "UEBERNOMMEN",
                    "ABGEBROCHEN"}) String status,
            Instant erstellt) {

        static LaufDto aus(ImportLauf lauf) {
            return new LaufDto(lauf.id(), lauf.art().code(), lauf.vollexport(), lauf.dateiname(), lauf.sha256(),
                    lauf.zeichensatz(), String.valueOf(lauf.trennzeichen()), lauf.zeilen(), lauf.fehler(),
                    lauf.uebersprungen(), lauf.status().name(), lauf.erstellt());
        }
    }

    @Schema(name = "ImportSpalte")
    public record SpalteDto(@Schema(description = "Position ab 0") int position, String name) {
    }

    @Schema(name = "ImportZuordnung")
    public record ZuordnungDto(
            @Schema(nullable = true, enumeration = {"21.CRM"}) String profil,
            @Schema(description = "Feld → Spaltenposition (ab 0)") Map<String, Integer> felder) {

        static ZuordnungDto aus(Zuordnung zuordnung) {
            return new ZuordnungDto(zuordnung.profil(), zuordnung.felder());
        }
    }

    @Schema(name = "ImportHochgeladen")
    public record HochgeladenDto(LaufDto lauf, List<SpalteDto> spalten,
            @Schema(nullable = true, description = "nur bei Status ZUORDNUNG_NOETIG") ZuordnungDto vorschlagZuordnung) {
    }

    @Schema(name = "ImportZuordnungGespeichert")
    public record GespeicherteZuordnungDto(@Schema(enumeration = {"kontakt", "verkaufschance"}) String art,
            @Schema(nullable = true, enumeration = {"21.CRM"}) String profil, Map<String, Integer> felder,
            Instant geaendert) {
    }

    @Schema(name = "ImportZuordnungBestaetigen")
    public record ZuordnenDto(
            @Schema(required = true, description = "Lauf, dessen Kopfzeile zugeordnet wird") String laufId,
            @Schema(nullable = true, enumeration = {"21.CRM"},
                    description = "Profil (nur Importart kontakt); die Positionen rechnet das Archiv aus der Kopfzeile")
            String profil,
            @Schema(nullable = true, description = "Feld → Spaltenposition (ab 0); übersteuert das Profil")
            Map<String, Integer> felder) {
    }

    @POST
    @Path("/crm")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @ErfordertAktion(Aktion.A12)
    @RolesAllowed("inhaber")
    @Operation(summary = "CSV aus dem CRM hochladen",
            description = "Erkennt Zeichensatz und Trennzeichen und speichert den Lauf. Unbekannte oder abweichende "
                    + "Kopfzeile → 200 mit Spalten und Vorschlag (Zuordnungsdialog); bekannte → 202 "
                    + "(VORSCHAU_AUSSTEHEND). Dateien über 5 MiB → 413 (DATEI_ZU_GROSS).")
    @APIResponse(responseCode = "200", description = "Zuordnung nötig", content = @Content(
            mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = HochgeladenDto.class)))
    @APIResponse(responseCode = "202", description = "Zuordnung bekannt, Vorschau ausstehend", content = @Content(
            mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = HochgeladenDto.class)))
    @APIResponse(responseCode = "413", description = "DATEI_ZU_GROSS: Datei über archiv.import.max-groesse (5 MiB)")
    @APIResponse(responseCode = "422", description = "CSV_UNGUELTIG oder CSV_LEER")
    public Response hochladen(@QueryParam("art") String art, @QueryParam("vollexport") String vollexport,
                              @RestForm("datei") @Schema(type = SchemaType.STRING, format = "binary") FileUpload datei) {
        aufloesung.pruefen();
        ImportArt importArt = art(art);
        boolean voll = vollexport(vollexport);
        if (datei == null || datei.uploadedFile() == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        CsvImport.Hochgeladen ergebnis = csvImport.hochladen(importArt, voll, datei.fileName(), datei.uploadedFile(),
                benutzer());
        ImportLauf lauf = ergebnis.lauf();
        List<SpalteDto> spalten = new ArrayList<>();
        for (int i = 0; i < lauf.kopf().spalten(); i++) {
            spalten.add(new SpalteDto(i, lauf.kopf().namen().get(i)));
        }
        HochgeladenDto antwort = new HochgeladenDto(LaufDto.aus(lauf), spalten,
                ergebnis.vorschlag().map(ZuordnungDto::aus).orElse(null));
        return Response.status(ergebnis.vorschlag().isPresent() ? Response.Status.OK : Response.Status.ACCEPTED)
                .entity(antwort).build();
    }

    @GET
    @Path("/crm/zuordnung")
    @ErfordertAktion(Aktion.A12)
    @RolesAllowed("inhaber")
    @Operation(summary = "Gespeicherte Spaltenzuordnung der Importart lesen")
    @APIResponse(responseCode = "404", description = "Keine Zuordnung gespeichert")
    public GespeicherteZuordnungDto zuordnung(@QueryParam("art") String art) {
        aufloesung.pruefen();
        GespeicherteZuordnung gespeichert = csvImport.zuordnung(art(art))
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        return new GespeicherteZuordnungDto(gespeichert.art().code(), gespeichert.zuordnung().profil(),
                gespeichert.zuordnung().felder(), gespeichert.geaendert());
    }

    @PUT
    @Path("/crm/zuordnung")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A12)
    @RolesAllowed("inhaber")
    @Operation(summary = "Spaltenzuordnung bestätigen und speichern",
            description = "Speichert die Zuordnung für Mandant und Importart und prüft die Zeilen des Laufs neu.")
    @APIResponse(responseCode = "200", description = "Gespeichert, Lauf VORSCHAU_AUSSTEHEND", content = @Content(
            mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = LaufDto.class)))
    @APIResponse(responseCode = "422", description = "CSV_UNGUELTIG: keine Spalte für Name bzw. Titel")
    public LaufDto zuordnen(@QueryParam("art") String art, ZuordnenDto zuordnung) {
        aufloesung.pruefen();
        ImportArt importArt = art(art);
        if (zuordnung == null || zuordnung.laufId() == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        UUID laufId;
        try {
            laufId = UUID.fromString(zuordnung.laufId());
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return LaufDto.aus(csvImport.zuordnen(importArt, laufId, zuordnung.profil(), zuordnung.felder()));
    }

    private static ImportArt art(String art) {
        return ImportArt.aus(art).orElseThrow(() -> new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG));
    }

    private static boolean vollexport(String wert) {
        if (wert == null || "false".equals(wert)) {
            return false;
        }
        if ("true".equals(wert)) {
            return true;
        }
        throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }

    private String benutzer() {
        return identitaet.isAnonymous() ? null : identitaet.getPrincipal().getName();
    }
}
