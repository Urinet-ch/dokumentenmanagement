package ch.urinet.archiv.wissen.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenLesen;
import ch.urinet.archiv.wissen.application.WissenLesenRepository;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/")
@Tag(name = "Wissensbasis")
@Produces(MediaType.APPLICATION_JSON)
public class WissensseiteResource {

    private static final int INDEX_STANDARD = 50;
    private static final int INDEX_MAX = 200;
    private static final int LOG_STANDARD = 50;
    private static final int LOG_MAX = 100;
    private static final int MAX_OFFSET = 100_000;
    // innerhalb des Wertebereichs von PostgreSQL timestamptz, sonst scheitert erst die Abfrage (500)
    private static final Instant CURSOR_MIN = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant CURSOR_MAX = Instant.parse("9999-12-31T23:59:59Z");

    private final WissenLesen lesen;
    private final WissensseiteAbrufProtokoll abrufProtokoll;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public WissensseiteResource(WissenLesen lesen, WissensseiteAbrufProtokoll abrufProtokoll,
                                MandantAufloesung aufloesung, SecurityIdentity identitaet) {
        this.lesen = lesen;
        this.abrufProtokoll = abrufProtokoll;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @Schema(name = "WissensIndex")
    public record IndexDto(List<IndexGruppeDto> gruppen, long gesamt, int offset, int groesse) {
    }

    @Schema(name = "WissensIndexGruppe")
    public record IndexGruppeDto(String typ, List<IndexZeileDto> seiten) {
    }

    @Schema(name = "WissensIndexZeile")
    public record IndexZeileDto(UUID id, String pfad, String typ, String titel, String beschreibung, String status,
                                boolean quellePruefen) {
    }

    @Schema(name = "Wissensseite")
    public record WissensseiteDto(UUID id, String pfad, String typ, String titel, String beschreibung, List<String> tags,
                                  String status, java.time.LocalDate veraltetAb, String vertraulichkeit,
                                  List<QuelleDto> quellen, String inhaltHtml, int version, String erzeugtVon,
                                  Instant erzeugtAm, String freigegebenVon, Instant freigegebenAm,
                                  boolean quellePruefen, boolean widerspruchOffen, UUID wissensquelleId) {
    }

    @Schema(name = "Wissensquelleneintrag")
    public record QuelleDto(String schluessel, UUID dokumentId, Integer seiteVon, Integer seiteBis, String auszug,
                            UUID quellSeiteId, String dokumentTitel, String dokumentDateiname, String quellSeitePfad,
                            String quellSeiteTitel) {
    }

    @Schema(name = "Wissensversion")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record VersionDto(int nummer, Map<String, Object> daten, String inhaltHtml, String erzeugtVon,
                             String freigegebenVon, Instant zeit, String grund, UUID entwurfId, String entwurfAnlass,
                             UUID dokumentId) {
    }

    @Schema(name = "Wissensversionen")
    public record VersionenDto(List<VersionDto> versionen) {
    }

    @Schema(name = "Wissensdiff")
    public record DiffDto(int von, int bis, List<String> vorher, List<String> entfernt, List<String> hinzugefuegt,
                          List<String> nachher) {
    }

    @Schema(name = "WissensLog")
    public record LogDto(long id, Instant zeit, String aktion, String akteur, List<VerweisDto> seiten,
                         String text, UUID dokumentId, UUID entwurfId) {
    }

    @Schema(name = "WissensLogVerweis")
    public record VerweisDto(UUID id, String pfad, String titel) {
    }

    @Schema(name = "WissensLogSeite")
    public record LogSeiteDto(List<LogDto> eintraege, String naechstesVor) {
    }

    @GET
    @Path("/wissensseiten")
    @ErfordertAktion(Aktion.A15)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Wissensseiten-Index",
            description = "Nach Seitentyp gruppiert. Filter und Zähler berücksichtigen nur sichtbare Seiten. "
                    + "Mit pfad wird stattdessen eine einzelne Seite abgerufen.")
    @APIResponse(responseCode = "200", description = "Index oder Seite")
    @APIResponse(responseCode = "400", description = "Ungültiger Filter oder Pagingwert")
    @APIResponse(responseCode = "404", description = "Seite zum Pfad nicht vorhanden")
    @Transactional
    public Response index(
            @QueryParam("typ") String typ,
            @QueryParam("tag") String tag,
            @QueryParam("q") String q,
            @QueryParam("pfad") String pfad,
            @QueryParam("dokumentId") String dokumentId,
            @QueryParam("offset") @Parameter(schema = @Schema(type = SchemaType.INTEGER, defaultValue = "0"))
            String offset,
            @QueryParam("groesse") @Parameter(schema = @Schema(type = SchemaType.INTEGER, defaultValue = "50"))
            String groesse) {
        aufloesung.pruefen();
        Benutzer benutzer = benutzer();
        if (pfad != null) {
            if (pfad.isBlank() || pfad.length() > 200) {
                throw ungueltigeAnfrage();
            }
            Wissensseite seite = lesen.seite(benutzer, pfad);
            abrufProtokoll.protokollieren(seite.id());
            return seitenAntwort(benutzer, seite);
        }
        Seitentyp seitentyp = seitentyp(typ);
        String suchbegriff = textFilter(q, 200);
        String schlagwort = textFilter(tag, 100);
        int start = zahl(offset, 0, MAX_OFFSET);
        int anzahl = zahl(groesse, INDEX_STANDARD, INDEX_MAX);
        UUID dokument = dokumentFilter(dokumentId);
        WissenLesen.IndexErgebnis ergebnis = lesen.index(benutzer, seitentyp, schlagwort, suchbegriff, dokument,
                start, anzahl);
        List<IndexGruppeDto> gruppen = ergebnis.gruppen().stream()
                .map(gruppe -> new IndexGruppeDto(gruppe.typ().datenbankwert(), gruppe.seiten().stream()
                        .map(WissensseiteResource::dto).toList()))
                .toList();
        return privateAntwort(new IndexDto(gruppen, ergebnis.gesamt(), ergebnis.offset(), ergebnis.groesse())).build();
    }

    @GET
    @Path("/wissensseiten/{id}")
    @ErfordertAktion(Aktion.A15)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.WISSEN_ANZEIGEN)
    @Operation(summary = "Wissensseite abrufen", description = "Sichtbare Seite mit gerendertem Inhalt und ETag.")
    @APIResponse(responseCode = "200", description = "Seite")
    @APIResponse(responseCode = "404", description = "Seite nicht vorhanden")
    public Response seite(@PathParam("id") String id) {
        aufloesung.pruefen();
        Benutzer benutzer = benutzer();
        return seitenAntwort(benutzer, lesen.seite(benutzer, uuid(benutzer, id)));
    }

    @GET
    @Path("/wissensseiten/{id}/versionen")
    @ErfordertAktion(Aktion.A15)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.WISSEN_ANZEIGEN)
    @Operation(summary = "Versionen einer Wissensseite",
            description = "Unsichtbare Versionen erscheinen ausschliesslich mit ihrer Nummer.")
    @APIResponse(responseCode = "200", description = "Versionen")
    @APIResponse(responseCode = "404", description = "Seite nicht vorhanden")
    public Response versionen(@PathParam("id") String id) {
        aufloesung.pruefen();
        Benutzer benutzer = benutzer();
        List<VersionDto> versionen = lesen.versionen(benutzer, uuid(benutzer, id)).stream()
                .map(zeile -> zeile.version().map(version -> dto(benutzer, version))
                        .orElseGet(() -> new VersionDto(zeile.nummer(), null, null, null, null, null, null, null,
                                null, null)))
                .toList();
        return privateAntwort(new VersionenDto(versionen)).build();
    }

    @GET
    @Path("/wissensseiten/{id}/versionen/{nummer}")
    @ErfordertAktion(Aktion.A15)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.WISSEN_ANZEIGEN)
    @Operation(summary = "Wissensseitenversion abrufen",
            description = "Antwortet mit 404, wenn die Seite oder ihre Version unsichtbar ist.")
    @APIResponse(responseCode = "200", description = "Version mit ETag")
    @APIResponse(responseCode = "404", description = "Version nicht vorhanden")
    public Response version(@PathParam("id") String id, @PathParam("nummer") String nummer) {
        aufloesung.pruefen();
        Benutzer benutzer = benutzer();
        WissenLesenRepository.WissensVersion version =
                lesen.version(benutzer, uuid(benutzer, id), positiveZahl(nummer));
        return privateAntwort(dto(benutzer, version)).tag(new EntityTag(Integer.toString(version.nummer()))).build();
    }

    @GET
    @Path("/wissensseiten/{id}/diff")
    @ErfordertAktion(Aktion.A15)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.WISSEN_ANZEIGEN)
    @Operation(summary = "Zwei sichtbare Wissensseitenversionen vergleichen")
    @APIResponse(responseCode = "200", description = "Diff mit ETag")
    @APIResponse(responseCode = "400", description = "Version fehlt oder ist ungültig")
    @APIResponse(responseCode = "404", description = "Seite oder Version nicht vorhanden")
    public Response diff(@PathParam("id") String id, @QueryParam("von") String von, @QueryParam("bis") String bis) {
        aufloesung.pruefen();
        Benutzer benutzer = benutzer();
        WissenLesen.Diff diff = lesen.diff(benutzer, uuid(benutzer, id), positiveZahl(von), positiveZahl(bis));
        return privateAntwort(new DiffDto(diff.von(), diff.bis(), diff.vorher(), diff.entfernt(),
                diff.hinzugefuegt(), diff.nachher()))
                .tag(new EntityTag(diff.von() + "-" + diff.bis())).build();
    }

    @GET
    @Path("/wissensseiten/{id}/eingehend")
    @ErfordertAktion(Aktion.A15)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.WISSEN_ANZEIGEN)
    @Operation(summary = "Eingehende Wissensseiten-Links")
    @APIResponse(responseCode = "200", description = "Sichtbare Seiten, die auf die Seite verweisen")
    @APIResponse(responseCode = "404", description = "Seite nicht vorhanden")
    public Response eingehend(@PathParam("id") String id) {
        aufloesung.pruefen();
        Benutzer benutzer = benutzer();
        return privateAntwort(lesen.eingehend(benutzer, uuid(benutzer, id)).stream()
                .map(WissensseiteResource::dto).toList()).build();
    }

    @GET
    @Path("/wissensbasis/log")
    @ErfordertAktion(Aktion.A15)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Wissensbasis-Log",
            description = "Neueste Einträge zuerst; Einträge ohne sichtbare Seite werden ausgelassen.")
    @APIResponse(responseCode = "200", description = "Gefilterte Logseite")
    @APIResponse(responseCode = "400", description = "Ungültiger Cursor oder Pagingwert")
    public Response log(
            @QueryParam("vor") @Parameter(description = "Cursor aus dem vorherigen Ergebnis") String vor,
            @QueryParam("groesse") @Parameter(schema = @Schema(type = SchemaType.INTEGER, defaultValue = "50"))
            String groesse) {
        aufloesung.pruefen();
        int anzahl = zahl(groesse, LOG_STANDARD, LOG_MAX);
        WissenLesenRepository.LogSeite seite = lesen.log(benutzer(), cursor(vor), anzahl);
        return privateAntwort(new LogSeiteDto(seite.eintraege().stream().map(WissensseiteResource::dto).toList(),
                seite.naechsterCursor().map(WissensseiteResource::cursor).orElse(null))).build();
    }

    private Response seitenAntwort(Benutzer benutzer, Wissensseite seite) {
        return privateAntwort(dto(benutzer, seite)).tag(new EntityTag(Integer.toString(seite.version()))).build();
    }

    private static Response.ResponseBuilder privateAntwort(Object entity) {
        CacheControl cache = new CacheControl();
        cache.setPrivate(true);
        cache.setNoCache(true);
        return Response.ok(entity).cacheControl(cache);
    }

    private WissensseiteDto dto(Benutzer benutzer, Wissensseite seite) {
        return new WissensseiteDto(seite.id(), seite.pfad(), seite.typ().datenbankwert(), seite.titel(),
                seite.beschreibung(), seite.tags(), seite.status().name(), seite.veraltetAb(),
                seite.vertraulichkeit().name(), quellen(benutzer, seite),
                lesen.inhaltDarstellen(benutzer, seite.inhalt()), seite.version(), seite.erzeugtVon(), seite.erzeugtAm(),
                seite.freigegebenVon(), seite.freigegebenAm(), seite.quellePruefen(), seite.widerspruchOffen(),
                seite.wissensquelleId());
    }

    private List<QuelleDto> quellen(Benutzer benutzer, Wissensseite seite) {
        Map<String, WissenLesen.QuellenAnzeige> anzeige = lesen.quellenAnzeige(benutzer, seite);
        return seite.quellen().stream().map(quelle -> dto(quelle, anzeige.get(quelle.schluessel()))).toList();
    }

    private static QuelleDto dto(Quelleneintrag quelle, WissenLesen.QuellenAnzeige anzeige) {
        return new QuelleDto(quelle.schluessel(), quelle.dokumentId(), quelle.seiteVon(), quelle.seiteBis(),
                quelle.auszug(), quelle.quellSeiteId(),
                anzeige == null ? null : anzeige.dokumentTitel(), anzeige == null ? null : anzeige.dokumentDateiname(),
                anzeige == null ? null : anzeige.seitePfad(), anzeige == null ? null : anzeige.seiteTitel());
    }

    private static IndexZeileDto dto(WissenLesenRepository.IndexZeile seite) {
        return new IndexZeileDto(seite.id(), seite.pfad(), seite.typ().datenbankwert(), seite.titel(),
                seite.beschreibung(), seite.status(), seite.quellePruefen());
    }

    private static UUID dokumentFilter(String wert) {
        if (wert == null) {
            return null;
        }
        try {
            return UUID.fromString(wert);
        } catch (IllegalArgumentException e) {
            throw ungueltigeAnfrage();
        }
    }

    private VersionDto dto(Benutzer benutzer, WissenLesenRepository.WissensVersion version) {
        return new VersionDto(version.nummer(), version.daten(), lesen.inhaltDarstellen(benutzer, version.inhalt()),
                version.erzeugtVon(), version.freigegebenVon(), version.zeit(), version.grund(), version.entwurfId(),
                version.entwurfAnlass(), version.dokumentId());
    }

    private static LogDto dto(WissenLesenRepository.LogEintrag eintrag) {
        String seiten = eintrag.seiten().stream().map(WissenLesenRepository.SeiteVerweis::titel)
                .reduce((a, b) -> a + ", " + b).orElse("");
        String text = switch (eintrag.aktion()) {
            case "INGEST" -> "Wissensquelle eingearbeitet";
            case "CREATION" -> "Seite erstellt";
            case "UPDATE" -> "Seite aktualisiert";
            case "DEPRECATION" -> "Seite als veraltet markiert";
            case "LINT" -> "Prüfung abgeschlossen";
            case "ANALYSE" -> "Analyse übernommen";
            default -> throw new IllegalStateException("Unbekannte Wissensbasis-Logaktion");
        };
        if (!seiten.isBlank()) {
            text += ": " + seiten;
        }
        return new LogDto(eintrag.id(), eintrag.zeit(), eintrag.aktion(), eintrag.akteur(),
                eintrag.seiten().stream().map(s -> new VerweisDto(s.id(), s.pfad(), s.titel())).toList(), text,
                eintrag.dokumentId(), eintrag.entwurfId());
    }

    private Benutzer benutzer() {
        return new Benutzer(identitaet.getPrincipal().getName(),
                Rolle.ausRealmRollen(identitaet.getRoles()).orElseThrow());
    }

    private static Seitentyp seitentyp(String wert) {
        if (wert == null || wert.isBlank()) {
            return null;
        }
        return Seitentyp.vonDatenbankwert(wert).orElseThrow(WissensseiteResource::ungueltigeAnfrage);
    }

    private static String textFilter(String wert, int max) {
        if (wert == null || wert.isBlank()) {
            return null;
        }
        String text = wert.trim();
        if (text.length() > max) {
            throw ungueltigeAnfrage();
        }
        return text;
    }

    private static int zahl(String wert, int standard, int max) {
        if (wert == null) {
            return standard;
        }
        try {
            int zahl = Integer.parseInt(wert);
            if (zahl < 0 || zahl > max || (max != MAX_OFFSET && zahl == 0)) {
                throw ungueltigeAnfrage();
            }
            return zahl;
        } catch (NumberFormatException e) {
            throw ungueltigeAnfrage();
        }
    }

    private static int positiveZahl(String wert) {
        try {
            int zahl = Integer.parseInt(wert);
            if (zahl < 1) {
                throw ungueltigeAnfrage();
            }
            return zahl;
        } catch (NumberFormatException | NullPointerException e) {
            throw ungueltigeAnfrage();
        }
    }

    /** Eine ungültige Seiten-ID gilt wie eine unsichtbare Seite: 404 mit {@code VERWEIGERT} (Req 14.4). */
    private UUID uuid(Benutzer benutzer, String wert) {
        try {
            return UUID.fromString(wert);
        } catch (IllegalArgumentException | NullPointerException e) {
            lesen.ungueltigeSeitenId(benutzer);
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
    }

    private static WissenLesenRepository.LogCursor cursor(String wert) {
        if (wert == null || wert.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(wert), StandardCharsets.UTF_8);
            String[] teile = decoded.split("\\n", -1);
            if (teile.length != 2) {
                throw ungueltigeAnfrage();
            }
            Instant zeit = Instant.parse(teile[0]);
            long id = Long.parseLong(teile[1]);
            if (id < 1 || zeit.isBefore(CURSOR_MIN) || zeit.isAfter(CURSOR_MAX)) {
                throw ungueltigeAnfrage();
            }
            return new WissenLesenRepository.LogCursor(zeit, id);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw ungueltigeAnfrage();
        }
    }

    private static String cursor(WissenLesenRepository.LogCursor cursor) {
        String wert = cursor.zeit() + "\n" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(wert.getBytes(StandardCharsets.UTF_8));
    }

    private static FachFehler ungueltigeAnfrage() {
        return new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }
}
