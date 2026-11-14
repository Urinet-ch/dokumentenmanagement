package ch.urinet.archiv.importe.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.application.CsvImport;
import ch.urinet.archiv.importe.application.ImportKontakte;
import ch.urinet.archiv.importe.domain.GespeicherteZuordnung;
import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.importe.domain.ImportZeile;
import ch.urinet.archiv.importe.domain.ZeilenPruefung.Ergebnis;
import ch.urinet.archiv.importe.domain.LaufStatus;
import ch.urinet.archiv.importe.domain.Zuordnung;
import ch.urinet.archiv.importe.domain.Zuordnungsvorschlag;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CSV-Import der CRM-Daten, Erkennung und Spaltenzuordnung (A12, nur {@code inhaber}; Req 2.18, 13.1, 13.2, 13.10,
 * 13.11), Vorschau (Aufgabe 4.2; Req 13.3–13.5) sowie Übernahme und Protokoll (Aufgabe 4.3; Req 13.6–13.9). Der
 * Mandant kommt nur aus der Anmeldung.
 */
@Path("/importe")
@Tag(name = "Importe")
@Produces(MediaType.APPLICATION_JSON)
public class ImportResource {

    private final CsvImport csvImport;
    private final ImportKontakte kontakte;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public ImportResource(CsvImport csvImport, ImportKontakte kontakte, MandantAufloesung aufloesung,
                          SecurityIdentity identitaet) {
        this.csvImport = csvImport;
        this.kontakte = kontakte;
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
            Instant erstellt,
            @Schema(nullable = true, description = "Vorschau: neue Kontakte; erst ab Status VORSCHAU") Integer neu,
            @Schema(nullable = true, description = "Vorschau: geänderte Kontakte") Integer geaendert,
            @Schema(nullable = true, description = "Vorschau: unveränderte Kontakte") Integer unveraendert,
            @Schema(nullable = true, description = "Vorschau: mögliche Dubletten") Integer dubletten,
            @Schema(nullable = true, description = "Vorschau: Zeilen, deren Kontakt inaktiv ist (Req 17.13); werden "
                    + "nicht übernommen") Integer inaktiv,
            @Schema(nullable = true, description = "Benutzer, der die Übernahme ausgelöst hat") String uebernommenVon,
            @Schema(nullable = true, description = "Ende der Übernahme; erst ab Status UEBERNOMMEN") Instant uebernommen,
            @Schema(nullable = true, description = "Übernahme: angelegte Kontakte bzw. Verkaufschancen") Integer angelegt,
            @Schema(nullable = true, description = "Übernahme: aktualisierte Kontakte bzw. Verkaufschancen")
            Integer aktualisiert,
            @Schema(nullable = true, description = "Übernahme: ausgelassene Zeilen (fehlerhaft, gelöscht, Dublette "
                    + "ohne Entscheid oder übersprungen, Kontakt fehlt)") Integer ausgelassen,
            @Schema(nullable = true, description = "Übernahme (nur Vollexport): neu als «nicht mehr im CRM-Export» "
                    + "gekennzeichnete Kontakte") Integer gekennzeichnet) {

        static LaufDto aus(ImportLauf lauf) {
            ImportLauf.Zaehler v = lauf.vorschau();
            ImportLauf.Uebernahme u = lauf.uebernahme();
            ImportLauf.Bilanz b = u == null ? null : u.bilanz();
            return new LaufDto(lauf.id(), lauf.art().code(), lauf.vollexport(), lauf.dateiname(), lauf.sha256(),
                    lauf.zeichensatz(), String.valueOf(lauf.trennzeichen()), lauf.zeilen(), lauf.fehler(),
                    lauf.uebersprungen(), lauf.status().name(), lauf.erstellt(), v == null ? null : v.neu(),
                    v == null ? null : v.geaendert(), v == null ? null : v.unveraendert(),
                    v == null ? null : v.dubletten(), v == null ? null : v.inaktiv(), u == null ? null : u.von(), u == null ? null : u.zeit(),
                    b == null ? null : b.angelegt(), b == null ? null : b.aktualisiert(),
                    b == null ? null : b.ausgelassen(), b == null ? null : b.gekennzeichnet());
        }
    }

    @Schema(name = "ImportZeile")
    public record ZeileDto(@Schema(description = "Zeile in der Datei (Kopfzeile = 1)") int nr,
            @Schema(nullable = true, enumeration = {"NEU", "GEAENDERT", "UNVERAENDERT", "DUBLETTE", "FEHLER",
                    "UEBERSPRUNGEN", "INAKTIV"}, description = "null: wartet auf Zuordnung oder Vorschau") String ergebnis,
            @Schema(nullable = true) String meldung,
            @Schema(nullable = true, description = "getroffener Kontakt bei GEAENDERT und UNVERAENDERT, der inaktive "
                    + "Kontakt bei INAKTIV") UUID kontaktId,
            @Schema(description = "Kandidaten einer möglichen Dublette") List<UUID> kandidaten,
            @Schema(nullable = true, description = "Rohwerte in Spaltenreihenfolge; fehlen bei zu langen Feldern")
            List<String> werte,
            @Schema(nullable = true, description = "Feld → Wert nach der Zuordnung des Laufs, wie ihn die Übernahme "
                    + "liest (Hilfsfelder aufgelöst, leere Werte fehlen); fehlt ohne Zuordnung oder ohne Rohwerte")
            Map<String, String> felder) {

        static ZeileDto aus(ImportZeile zeile, ImportLauf lauf) {
            Map<String, String> felder = lauf.zuordnung() == null || zeile.werte() == null ? null
                    : lauf.zuordnung().werte(lauf.art(), zeile.werte());
            return new ZeileDto(zeile.nr(), zeile.ergebnis() == Ergebnis.OFFEN ? null : zeile.ergebnis().name(),
                    zeile.meldung(), zeile.kontaktId(), zeile.kandidaten(), zeile.werte(), felder);
        }
    }

    @Schema(name = "ImportKontakt", description = "Kurzangaben eines vorhandenen Kontakts (Treffer oder Kandidat)")
    public record KontaktKurzDto(@Schema(nullable = true) String crmId,
            @Schema(enumeration = {"PERSON", "ORGANISATION"}) String typ, String name,
            @Schema(nullable = true) String vorname, @Schema(nullable = true) String plz,
            @Schema(nullable = true) String ort, @Schema(nullable = true) String email) {

        static KontaktKurzDto aus(Kontakt k) {
            return new KontaktKurzDto(k.crmId(), k.typ().name(), k.name(), k.vorname(), k.plz(), k.ort(), k.email());
        }
    }

    @Schema(name = "ImportLaufMitZeilen")
    public record LaufSeiteDto(LaufDto lauf, List<ZeileDto> eintraege,
            @Schema(description = "Anzahl Zeilen des Laufs; mit Filter nur die mit diesem Ergebnis") long gesamt,
            int seite, int groesse,
            @Schema(description = "Kontakt-ID → Kurzangaben für kontaktId und kandidaten der Zeilen dieser Seite")
            Map<UUID, KontaktKurzDto> kontakte,
            @Schema(nullable = true, description = "Spalten der Kopfzeile; nur bei Status ZUORDNUNG_NOETIG")
            List<SpalteDto> spalten,
            @Schema(nullable = true, description = "nur bei Status ZUORDNUNG_NOETIG") ZuordnungDto vorschlagZuordnung) {
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

    @Schema(name = "ImportUebernehmen")
    public record UebernehmenDto(
            @Schema(nullable = true, description = "Zeile → NEU, ZUSAMMENFUEHREN:<kontaktId> oder UEBERSPRINGEN; nur für "
                    + "Zeilen mit Ergebnis DUBLETTE, ZUSAMMENFUEHREN nur mit einem ihrer Kandidaten. Dubletten ohne "
                    + "Entscheid werden übersprungen.") Map<String, String> dublettenEntscheide) {
    }

    @Schema(name = "ImportLaeufe")
    public record LaeufeDto(List<LaufDto> eintraege, @Schema(description = "Anzahl Läufe des Mandanten") long gesamt,
            int seite, int groesse) {
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
        HochgeladenDto antwort = new HochgeladenDto(LaufDto.aus(lauf), spalten(lauf),
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
    @APIResponse(responseCode = "409", description = "STATUS_KONFLIKT: Lauf nicht mehr zuzuordnen oder gesperrt, "
            + "weil seine Übernahme gerade läuft")
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

    @GET
    @Path("/{id}")
    @ErfordertAktion(Aktion.A12)
    @RolesAllowed("inhaber")
    @Operation(summary = "Import-Lauf mit Vorschau lesen",
            description = "Lauf mit den Zählern der Vorschau (neu, geändert, unverändert, mögliche Dublette, "
                    + "fehlerhaft) und einer Seite seiner Zeilen (Req 13.3), mit ergebnis nur Zeilen mit diesem "
                    + "Ergebnis. seite ab 0, groesse 1–100. Die Zähler der Vorschau fehlen, bis der Auftrag "
                    + "CSV_VORSCHAU fertig ist (Status VORSCHAU). Bei Status ZUORDNUNG_NOETIG kommen Spalten und "
                    + "Vorschlag wie beim Upload mit (Zuordnungsdialog nach einem Neuladen).")
    @APIResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = LaufSeiteDto.class)))
    @APIResponse(responseCode = "400", description = "ANFRAGE_UNGUELTIG: seite, groesse oder ergebnis ungültig")
    @APIResponse(responseCode = "404", description = "NICHT_GEFUNDEN: kein Lauf mit dieser ID im Mandanten")
    public LaufSeiteDto lauf(@PathParam("id") String id, @QueryParam("seite") @DefaultValue("0") int seite,
                             @QueryParam("groesse") @DefaultValue("50") int groesse,
                             @QueryParam("ergebnis") @Schema(enumeration = {"NEU", "GEAENDERT", "UNVERAENDERT",
                                     "DUBLETTE", "FEHLER", "UEBERSPRUNGEN", "INAKTIV"}) String ergebnis) {
        aufloesung.pruefen();
        UUID laufId;
        try {
            laufId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        CsvImport.LaufSeite seiteMitZeilen = csvImport.lauf(laufId, ergebnisFilter(ergebnis), seite, groesse);
        ImportLauf lauf = seiteMitZeilen.lauf();
        Set<UUID> verweise = new LinkedHashSet<>();
        for (ImportZeile zeile : seiteMitZeilen.zeilen()) {
            if (zeile.kontaktId() != null) {
                verweise.add(zeile.kontaktId());
            }
            verweise.addAll(zeile.kandidaten());
        }
        Map<UUID, KontaktKurzDto> kurz = new LinkedHashMap<>();
        kontakte.zu(verweise).forEach((kontaktId, kontakt) -> kurz.put(kontaktId, KontaktKurzDto.aus(kontakt)));
        boolean zuordnen = lauf.status() == LaufStatus.ZUORDNUNG_NOETIG;
        return new LaufSeiteDto(LaufDto.aus(lauf),
                seiteMitZeilen.zeilen().stream().map(z -> ZeileDto.aus(z, lauf)).toList(), seiteMitZeilen.gesamt(),
                seite, groesse, kurz, zuordnen ? spalten(lauf) : null,
                zuordnen ? ZuordnungDto.aus(Zuordnungsvorschlag.fuer(lauf.art(), lauf.kopf())) : null);
    }

    private static Ergebnis ergebnisFilter(String ergebnis) {
        if (ergebnis == null || ergebnis.isEmpty()) {
            return null;
        }
        for (Ergebnis e : Ergebnis.values()) {
            if (e != Ergebnis.OFFEN && e.name().equals(ergebnis)) {
                return e;
            }
        }
        throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }

    private static List<SpalteDto> spalten(ImportLauf lauf) {
        List<SpalteDto> spalten = new ArrayList<>();
        for (int i = 0; i < lauf.kopf().spalten(); i++) {
            spalten.add(new SpalteDto(i, lauf.kopf().namen().get(i)));
        }
        return spalten;
    }

    @POST
    @Path("/{id}/uebernehmen")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A12)
    @RolesAllowed("inhaber")
    @Operation(summary = "Lauf übernehmen",
            description = "Reiht die Übernahme eines Laufs im Status VORSCHAU ein (Auftrag CSV_UEBERNEHMEN, eine "
                    + "Transaktion; Req 13.6–13.9). Neue Kontakte bzw. Verkaufschancen werden angelegt, geänderte "
                    + "aktualisiert (die CSV gewinnt, leere Zellen überschreiben nichts), mögliche Dubletten nur nach "
                    + "Entscheid. Gelöscht wird nie; beim Vollexport werden Kontakte mit CRM-ID, die fehlen, als "
                    + "«nicht mehr im CRM-Export» gekennzeichnet. Ein erneuter Aufruf vor dem Ende ersetzt die "
                    + "Entscheide.")
    @APIResponse(responseCode = "202", description = "Übernahme eingereiht; Status bleibt VORSCHAU bis zum Ende",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = LaufDto.class)))
    @APIResponse(responseCode = "400", description = "ANFRAGE_UNGUELTIG: Entscheid ungültig, für eine Zeile ohne "
            + "Dublette, mit einem Kontakt ausserhalb der Kandidaten, zweimal für dieselbe Zeile oder bei "
            + "Verkaufschancen")
    @APIResponse(responseCode = "404", description = "NICHT_GEFUNDEN: kein Lauf mit dieser ID im Mandanten")
    @APIResponse(responseCode = "409", description = "STATUS_KONFLIKT: Lauf nicht im Status VORSCHAU oder gesperrt, "
            + "weil seine Übernahme gerade läuft")
    public Response uebernehmen(@PathParam("id") String id, UebernehmenDto anfrage) {
        aufloesung.pruefen();
        UUID laufId = laufId(id);
        Map<Integer, String> entscheide = new HashMap<>();
        if (anfrage != null && anfrage.dublettenEntscheide() != null) {
            // «5» und «05» sind dieselbe Zeile; zwei Entscheide dafür sind ungültig, nicht der zweite gewinnt
            anfrage.dublettenEntscheide().forEach((zeile, entscheid) -> {
                if (entscheide.putIfAbsent(zeile(zeile), entscheid) != null) {
                    throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
                }
            });
        }
        return Response.accepted(LaufDto.aus(csvImport.uebernehmen(laufId, entscheide, benutzer()))).build();
    }

    @GET
    @ErfordertAktion(Aktion.A12)
    @RolesAllowed("inhaber")
    @Operation(summary = "Import-Läufe mit Protokoll lesen",
            description = "Läufe des Mandanten, die neuesten zuerst, mit Dateiname, Prüfsumme, Zeit, Benutzer der "
                    + "Übernahme und Zählern (Req 13.8). seite ab 0, groesse 1–100.")
    @APIResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = LaeufeDto.class)))
    public LaeufeDto laeufe(@QueryParam("seite") @DefaultValue("0") int seite,
                            @QueryParam("groesse") @DefaultValue("50") int groesse) {
        aufloesung.pruefen();
        CsvImport.Laeufe ergebnis = csvImport.laeufe(seite, groesse);
        return new LaeufeDto(ergebnis.laeufe().stream().map(LaufDto::aus).toList(), ergebnis.gesamt(), seite,
                groesse);
    }

    private static UUID laufId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
    }

    private static int zeile(String zeile) {
        try {
            return Integer.parseInt(zeile);
        } catch (NumberFormatException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
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
