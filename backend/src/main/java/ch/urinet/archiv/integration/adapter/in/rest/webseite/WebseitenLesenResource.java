package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.application.webseite.DateiAuslieferung;
import ch.urinet.archiv.integration.application.webseite.DateiAuslieferung.Auslieferung;
import ch.urinet.archiv.integration.application.webseite.WebseiteLesen;
import ch.urinet.archiv.integration.application.webseite.WebseiteSuche;
import ch.urinet.archiv.integration.domain.webseite.ListenFilter;
import ch.urinet.archiv.integration.domain.webseite.WebseiteDokument;
import ch.urinet.archiv.integration.domain.webseite.WebseiteReihe;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSeite;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSortierung;
import ch.urinet.archiv.integration.domain.webseite.WebseiteTreffer;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.sicherheit.application.WebseitenZugang;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lesende Endpunkte der Webseiten-Schnittstelle (Spec 03, Aufgabe 11.2, Req 5.1, 5.4, 5.8, 5.10, 5.11) unter
 * {@code /public/v1}, erreichbar über den Reroute von {@code WebseitenRoute}. Jede Methode verlangt einen Lese-Schlüssel
 * ({@code @WebseitenZugang(LESEN)}); die Stufe der Anfrage liefert {@link LeseAnfrage} (Header {@code X-Archiv-Zugriff},
 * Kappung und Fehler im {@link LeseZugriffFilter}). Parameter kommen als Text und werden hier gelesen, damit jede
 * ungültige Angabe 400 {@code ANFRAGE_UNGUELTIG} ergibt; unbekannte Parameter werden ignoriert, und kein Parameter kann
 * den festen Filter ändern ({@code zugriff}, {@code veroeffentlicht}, {@code status} und andere feste Namen werden nie
 * gelesen). Alle Antworten gehen durch den festen Filter mit den Stufen der Anfrage; ein Dokument ausserhalb davon ist
 * 404 {@code NICHT_GEFUNDEN}. Die Antworten tragen nur {@code dokumentId}/{@code id} und nie eine URL einer Seite der
 * Webseite (Req 5.11).
 */
@Path("/public/v1")
@Produces(MediaType.APPLICATION_JSON)
public class WebseitenLesenResource {

    static final String REAL_IP = "X-Real-Ip";

    /**
     * Die Header der lesenden Anfrage für den Vertrag; gelesen und geprüft werden sie im {@link LeseZugriffFilter},
     * nicht hier.
     */
    public static class LeseKopf {
        @HeaderParam("X-Archiv-Zugriff")
        @Parameter(description = "Zugriffsstufe der Anfrage; fehlt der Header, gilt oeffentlich; über der Stufe des "
                + "Schlüssels 403, unbekannt 400",
                schema = @Schema(type = SchemaType.STRING, enumeration = {"oeffentlich", "mitglieder", "vorstand"}))
        public String zugriff;

        @HeaderParam("X-Archiv-Client")
        @Parameter(description = "SHA-256 aus IP und Tages-Salz des Besuchers, von der Webseite berechnet "
                + "(64 Hexziffern in Kleinbuchstaben); die IP selbst bekommt das Archiv nie",
                schema = @Schema(type = SchemaType.STRING))
        public String client;
    }

    private final WebseiteLesen lesen;
    private final WebseiteSuche suche;
    private final DateiAuslieferung auslieferung;
    private final LeseAnfrage anfrage;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public WebseitenLesenResource(WebseiteLesen lesen, WebseiteSuche suche, DateiAuslieferung auslieferung,
                                  LeseAnfrage anfrage,
                                  MandantAufloesung aufloesung, SecurityIdentity identitaet) {
        this.lesen = lesen;
        this.suche = suche;
        this.auslieferung = auslieferung;
        this.anfrage = anfrage;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @Schema(name = "WebseiteReihe", description = "Reihe (Dokumentart) eines Dokuments")
    public record ReiheDto(@Schema(required = true) String code, @Schema(required = true) String name) {
    }

    @Schema(name = "WebseiteDokument", description = "Dokument in der Liste; bezeichnet nur mit der ID, nie mit einer URL")
    public record DokumentDto(
            @Schema(required = true) UUID id,
            @Schema(nullable = true) String titel,
            @Schema(nullable = true) ReiheDto reihe,
            @Schema(nullable = true, description = "Ausgabe innerhalb der Reihe, z. B. Nummer") String ausgabe,
            @Schema(nullable = true) Integer jahr,
            @Schema(nullable = true, description = "Erscheinungsdatum; fehlt, wenn unbekannt") LocalDate datum,
            @Schema(required = true, enumeration = {"oeffentlich", "mitglieder", "vorstand"},
                    description = "Zugriffsstufe des Dokuments") String zugriff,
            @Schema(nullable = true, description = "Zahl der Seiten, wenn bekannt") Integer seiten) {
    }

    @Schema(name = "WebseiteDokumentListe", description = "Seite der Liste veröffentlichter Dokumente")
    public record ListeDto(
            @Schema(required = true) List<DokumentDto> eintraege,
            @Schema(required = true, description = "Nummer dieser Seite, ab 1") int seite,
            @Schema(required = true) int proSeite,
            @Schema(required = true, description = "Zahl aller Dokumente, die die Anfrage liefert") long gesamt) {
    }

    @Schema(name = "WebseiteDokumentDetails", description = "Dokument mit Auszug")
    public record DetailsDto(
            @Schema(required = true) UUID id,
            @Schema(nullable = true) String titel,
            @Schema(nullable = true) ReiheDto reihe,
            @Schema(nullable = true) String ausgabe,
            @Schema(nullable = true) Integer jahr,
            @Schema(nullable = true) LocalDate datum,
            @Schema(required = true, enumeration = {"oeffentlich", "mitglieder", "vorstand"}) String zugriff,
            @Schema(nullable = true) Integer seiten,
            @Schema(nullable = true, description = "Anfang des Textes, höchstens 300 Zeichen; fehlt ohne Text")
            String auszug) {
    }

    @Schema(name = "WebseiteFundstelle", description = "Stelle des besten Treffers im Dokument")
    public record FundstelleDto(@Schema(required = true, description = "PDF-Seite, ab 1; die Webseite verlinkt mit #page=<n> an die Datei, "
            + "das Archiv liefert keine Seiten-URL") int seite) {
    }

    @Schema(name = "WebseiteTreffer", description = "Dokument als Treffer der Suche")
    public record TrefferDto(
            @Schema(required = true) UUID id,
            @Schema(nullable = true) String titel,
            @Schema(nullable = true) ReiheDto reihe,
            @Schema(nullable = true) String ausgabe,
            @Schema(nullable = true) Integer jahr,
            @Schema(nullable = true) LocalDate datum,
            @Schema(required = true, enumeration = {"oeffentlich", "mitglieder", "vorstand"}) String zugriff,
            @Schema(nullable = true) Integer seiten,
            @Schema(nullable = true, description = "Textstelle zum Suchbegriff aus dem Text, höchstens 300 Zeichen; "
                    + "ohne Dateinamen") String auszug,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Fehlt, wenn die Seite unbekannt ist (Treffer nur aus dem Volltext, Text ohne "
                    + "Seitentrenner oder Seite ausserhalb von seiten)") FundstelleDto fundstelle,
            @Schema(required = true, description = "Platz in dieser Antwort, ab 1") int rang) {
    }

    @Schema(name = "WebseiteSuchergebnis", description = "Treffer der Hybridsuche, höchstens 30, ohne Blättern")
    public record SucheDto(
            @Schema(required = true) List<TrefferDto> treffer,
            @Schema(required = true, description = "true, wenn nur im Volltext gesucht wurde (Rückfall, ohne "
                    + "semantische Suche)") boolean degradiert) {
    }

    @Schema(name = "WebseiteDateiLink", description = "Vorsignierte Adresse der Datei, kurz gültig")
    public record DateiLinkDto(
            @Schema(required = true, description = "Adresse der PDF-Datei; bis gueltigBis ein Zugangsschlüssel, "
                    + "nicht weitergeben, nicht speichern, nicht loggen") String url,
            @Schema(required = true, description = "Ablauf, höchstens 5 Minuten nach der Anfrage") Instant gueltigBis) {

        @Override
        public String toString() {
            return "DateiLinkDto[url=***, gueltigBis=" + gueltigBis + "]";
        }
    }

    @Schema(name = "WebseiteFilter", description = "Auswahl für die Filter der Webseite")
    public record FilterDto(
            @Schema(required = true, description = "Reihen mit mindestens einem sichtbaren Dokument")
            List<ReiheDto> reihen) {
    }

    @Schema(name = "WebseiteUmleitung", description = "Dokument zu einer alten URL")
    public record UmleitungDto(@Schema(required = true) UUID dokumentId) {
    }

    @GET
    @Path("/dokumente")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    @Operation(summary = "Veröffentlichte Dokumente auflisten",
            description = "Dokumente, die der feste Filter bis zur Stufe der Anfrage liefert (archiviert, "
                    + "veröffentlicht, Vertraulichkeit NORMAL, Zugriffsstufe bis zur gemeldeten Stufe). Filter nach "
                    + "reihe (Code), jahr und ausgabe; sortierung datum_ab (Standard), datum_auf oder titel. "
                    + "Unbekannte Parameter werden ignoriert.")
    @APIResponse(responseCode = "200", description = "Seite der Liste",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ListeDto.class)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public ListeDto liste(@BeanParam LeseKopf kopf,
            @QueryParam("reihe") @Parameter(description = "Code der Reihe, z. B. aus /public/v1/filter") String reihe,
            @QueryParam("jahr") @Parameter(description = "Jahr, vier Ziffern") String jahr,
            @QueryParam("ausgabe") @Parameter(description = "Ausgabe innerhalb der Reihe") String ausgabe,
            @QueryParam("seite") @Parameter(description = "Seite ab 1 (Standard 1, höchstens 9999)",
                    schema = @Schema(type = SchemaType.INTEGER, format = "int32", minimum = "1")) String seite,
            @QueryParam("pro_seite") @Parameter(description = "Einträge je Seite, 1 bis 100 (Standard 20)",
                    schema = @Schema(type = SchemaType.INTEGER, format = "int32", minimum = "1", maximum = "100"))
            String proSeite,
            @QueryParam("sortierung") @Parameter(description = "Sortierung (Standard datum_ab)",
                    schema = @Schema(type = SchemaType.STRING, enumeration = {"datum_ab", "datum_auf", "titel"}))
            String sortierung) {
        WebseiteSeite ergebnis = lesen.liste(anfrage.zugriff(), new ListenFilter(reihe, jahr, ausgabe),
                sortierung(sortierung), zahl(seite, 1), zahl(proSeite, WebseiteLesen.PRO_SEITE_STANDARD));
        return new ListeDto(ergebnis.eintraege().stream().map(WebseitenLesenResource::dto).toList(), ergebnis.seite(),
                ergebnis.proSeite(), ergebnis.gesamt());
    }

    @GET
    @Path("/suche")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    @WebseitenGrenze(ch.urinet.archiv.integration.domain.webseite.Grenzklasse.SUCHE)
    @Operation(summary = "Dokumente suchen",
            description = "Hybridsuche (Volltext und semantische Suche mit Reranker) über die Dokumente, die der feste "
                    + "Filter bis zur Stufe der Anfrage liefert, ohne Dateinamen. q ist Pflicht (höchstens 200 "
                    + "Zeichen). Höchstens 30 Treffer ohne Blättern; degradiert heisst, dass nur im Volltext gesucht "
                    + "wurde. Die Fundstelle fehlt, wenn die Seite unbekannt ist.")
    @APIResponse(responseCode = "200", description = "Treffer",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SucheDto.class)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public SucheDto suchen(@BeanParam LeseKopf kopf,
            @QueryParam("q") @Parameter(required = true, description = "Suchbegriff (Websuche-Syntax), höchstens 200 "
                    + "Zeichen") String q,
            @QueryParam("reihe") @Parameter(description = "Code der Reihe") String reihe,
            @QueryParam("jahr") @Parameter(description = "Jahr, vier Ziffern") String jahr,
            @QueryParam("ausgabe") @Parameter(description = "Ausgabe innerhalb der Reihe") String ausgabe) {
        WebseiteSuche.Ergebnis ergebnis = suche.suchen(anfrage.zugriff(), q, new ListenFilter(reihe, jahr, ausgabe));
        return new SucheDto(ergebnis.treffer().stream().map(WebseitenLesenResource::dto).toList(),
                ergebnis.degradiert());
    }

    @GET
    @Path("/dokumente/{id}")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    @Operation(summary = "Details eines Dokuments",
            description = "Dokument mit Auszug. Ein Dokument ausserhalb des festen Filters (nicht veröffentlicht, "
                    + "höhere Stufe, fremder Mandant) und eine unbekannte oder ungültige ID ergeben dieselbe Antwort 404.")
    @APIResponse(responseCode = "200", description = "Dokument",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = DetailsDto.class)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public DetailsDto details(@BeanParam LeseKopf kopf, @PathParam("id") String id) {
        WebseiteDokument d = lesen.details(anfrage.zugriff(), uuid(id));
        return new DetailsDto(d.id(), d.titel(), reihe(d), d.ausgabe(), d.jahr(), d.datum(), d.zugriff(), d.seiten(),
                d.auszug());
    }

    @GET
    @Path("/dokumente/{id}/datei")
    @Produces({MediaType.APPLICATION_JSON, DateiAuslieferung.MEDIENTYP})
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    @Operation(summary = "Datei eines veröffentlichten Dokuments",
            description = "Gemischte Auslieferung nach der Stufe des Dokuments (Req 13): Stufe oeffentlich liefert 200 "
                    + "application/json mit der vorsignierten Adresse der PDF-Datei, höchstens 5 Minuten gültig; "
                    + "mitglieder und vorstand liefert 200 application/pdf als Stream, nie eine Speicher-Adresse. Der "
                    + "Stream trägt Content-Type, Content-Length, Cache-Control: private, no-store, "
                    + "X-Content-Type-Options: nosniff, Content-Disposition: inline ohne Dateinamen und Accept-Ranges: "
                    + "none; ein Range-Kopf wird ignoriert (immer die ganze Datei, 200). Jeder Abruf wird mit gekürzter "
                    + "IP protokolliert, bevor er beginnt. Ein Dokument über der Stufe der Anfrage oder ein "
                    + "unsichtbares Dokument ist 404. 429 ZU_VIELE_ANFRAGEN mit Retry-After 5, wenn die Höchstzahl "
                    + "gleichzeitiger Streams (gesamt oder je Schlüssel) läuft. Ein Stream, der 30 Sekunden keinen "
                    + "Block weitergibt oder länger als 15 Minuten läuft, wird abgebrochen.")
    @APIResponse(responseCode = "200", description = "Adresse der Datei (Stufe oeffentlich) oder die Datei als Stream",
            content = {
                    @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = DateiLinkDto.class)),
                    @Content(mediaType = DateiAuslieferung.MEDIENTYP,
                            schema = @Schema(type = SchemaType.STRING, format = "binary"))})
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public Response datei(@BeanParam LeseKopf kopf, @PathParam("id") String id,
                          @HeaderParam(REAL_IP) @Parameter(hidden = true) String ip,
                          @Context HttpServerRequest http) {
        if (http.method() == io.vertx.core.http.HttpMethod.HEAD) {
            // das Framework führte sonst den GET-Handler aus: Platz und Protokolleintrag, ohne dass je ein Byte fliesst
            return Response.status(Response.Status.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "GET").build();
        }
        Auslieferung ergebnis = auslieferung.ausliefern(aufloesung.pruefen(), anfrage.zugriff(), uuid(id),
                Optional.ofNullable(identitaet.getPrincipal().getName()), IpAdresse.aus(ip),
                () -> http.response().close());
        return switch (ergebnis) {
            case DateiAuslieferung.Link link -> Response.ok(new DateiLinkDto(link.url().url().toString(),
                    link.url().gueltigBis())).type(MediaType.APPLICATION_JSON_TYPE).build();
            case DateiAuslieferung.Strom strom -> {
                try {
                    StreamingOutput ausgabe = strom.lauf()::senden;
                    yield Response.ok(ausgabe, DateiAuslieferung.MEDIENTYP)
                            .header(HttpHeaders.CONTENT_LENGTH, strom.lauf().laenge())
                            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                            .header("X-Content-Type-Options", "nosniff")
                            // ohne Dateinamen: der Name käme aus Daten (Req 13.5, Spec 01, 3.12)
                            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                            .header("Accept-Ranges", "none")
                            .build();
                } catch (RuntimeException e) {
                    // der Lauf hält Platz und Speicherverbindung; ohne senden() gäbe sie sonst erst der Leerlauf frei
                    strom.lauf().abbrechen();
                    throw e;
                }
            }
        };
    }

    @GET
    @Path("/filter")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    @Operation(summary = "Werte für die Filter",
            description = "Reihen (Code und Name der aktiven Dokumentarten), zu denen der feste Filter bis zur Stufe "
                    + "der Anfrage mindestens ein Dokument liefert; eine Reihe oder ein Wert einer höheren Stufe "
                    + "wird nie genannt.")
    @APIResponse(responseCode = "200", description = "Filter",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = FilterDto.class)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public FilterDto filter(@BeanParam LeseKopf kopf) {
        List<WebseiteReihe> reihen = lesen.reihen(anfrage.zugriff());
        return new FilterDto(reihen.stream().map(r -> new ReiheDto(r.code(), r.name())).toList());
    }

    @GET
    @Path("/umleitung")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    @Operation(summary = "Dokument zu einer alten URL",
            description = "Zuordnung alte URL der bisherigen Webseite zur Dokument-ID, nur für Dokumente, die der feste "
                    + "Filter bis zur Stufe der Anfrage liefert; sonst 404. Die URL darf mit Schema und Host stehen; "
                    + "geprüft wird nur der Pfad (ohne Anfrage und Fragment, Prozent-Kodierung aufgelöst, ohne "
                    + "abschliessenden Schrägstrich).")
    @APIResponse(responseCode = "200", description = "Dokument",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = UmleitungDto.class)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "429", ref = "ZuVieleAnfragen")
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public UmleitungDto umleitung(@BeanParam LeseKopf kopf, @QueryParam("url") @Parameter(required = true,
            description = "Alte URL oder ihr Pfad") String url) {
        return new UmleitungDto(lesen.umleitung(anfrage.zugriff(), url));
    }

    private static DokumentDto dto(WebseiteDokument d) {
        return new DokumentDto(d.id(), d.titel(), reihe(d), d.ausgabe(), d.jahr(), d.datum(), d.zugriff(), d.seiten());
    }

    private static TrefferDto dto(WebseiteTreffer t) {
        WebseiteDokument d = t.dokument();
        return new TrefferDto(d.id(), d.titel(), reihe(d), d.ausgabe(), d.jahr(), d.datum(), d.zugriff(), d.seiten(),
                d.auszug(), t.fundstelleSeite() == null ? null : new FundstelleDto(t.fundstelleSeite()), t.rang());
    }

    private static ReiheDto reihe(WebseiteDokument d) {
        return d.reihe() == null ? null : new ReiheDto(d.reihe().code(), d.reihe().name());
    }

    /** Ungültige ID ist wie eine unbekannte: 404, ohne Hinweis. */
    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
    }

    private static WebseiteSortierung sortierung(String code) {
        if (code == null || code.isEmpty()) {
            return WebseiteSortierung.DATUM_AB;
        }
        return WebseiteSortierung.ausCode(code).orElseThrow(() -> new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG));
    }

    private static int zahl(String wert, int standard) {
        if (wert == null || wert.isEmpty()) {
            return standard;
        }
        try {
            return Integer.parseInt(wert);
        } catch (NumberFormatException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }
}
