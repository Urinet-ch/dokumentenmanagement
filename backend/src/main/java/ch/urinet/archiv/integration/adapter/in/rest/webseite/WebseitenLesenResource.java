package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.application.webseite.WebseiteDatei;
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
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
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
    private final WebseiteDatei datei;
    private final LeseAnfrage anfrage;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public WebseitenLesenResource(WebseiteLesen lesen, WebseiteSuche suche, WebseiteDatei datei, LeseAnfrage anfrage,
                                  MandantAufloesung aufloesung, SecurityIdentity identitaet) {
        this.lesen = lesen;
        this.suche = suche;
        this.datei = datei;
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
    public record FundstelleDto(@Schema(required = true, description = "PDF-Seite, ab 1") int seite) {
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
            @Schema(description = "Fehlt, wenn die Seite unbekannt ist (Treffer nur aus dem Volltext oder Text ohne "
                    + "Seitentrenner)") FundstelleDto fundstelle,
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
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public DetailsDto details(@BeanParam LeseKopf kopf, @PathParam("id") String id) {
        WebseiteDokument d = lesen.details(anfrage.zugriff(), uuid(id));
        return new DetailsDto(d.id(), d.titel(), reihe(d), d.ausgabe(), d.jahr(), d.datum(), d.zugriff(), d.seiten(),
                d.auszug());
    }

    @GET
    @Path("/dokumente/{id}/datei")
    @Authenticated
    @WebseitenZugang(WebseitenZugang.Art.LESEN)
    @Operation(summary = "Datei-Link eines öffentlichen Dokuments",
            description = "Vorsignierte Adresse der PDF-Datei, höchstens 5 Minuten gültig; jeder Abruf wird mit "
                    + "gekürzter IP protokolliert. Nur für Dokumente der Stufe oeffentlich; für andere Dokumente gibt "
                    + "es hier keine Adresse (404), ihr Abruf folgt als Stream mit einer späteren Fassung.")
    @APIResponse(responseCode = "200", description = "Adresse der Datei",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = DateiLinkDto.class)))
    @APIResponse(responseCode = "400", ref = "AnfrageUngueltig")
    @APIResponse(responseCode = "401", ref = "NichtAngemeldet")
    @APIResponse(responseCode = "403", ref = "NichtErlaubt")
    @APIResponse(responseCode = "404", ref = "NichtGefunden")
    @APIResponse(responseCode = "503", ref = "Ueberlastet")
    public DateiLinkDto datei(@BeanParam LeseKopf kopf, @PathParam("id") String id, @HeaderParam(REAL_IP) @Parameter(hidden = true) String ip) {
        // die Sicht der Datei (feste Filter mit Stufe oeffentlich) ist enger als die der Anfrage und gilt immer
        VorsignierteUrl url = datei.link(aufloesung.pruefen(), uuid(id),
                Optional.ofNullable(identitaet.getPrincipal().getName()), IpAdresse.aus(ip));
        return new DateiLinkDto(url.url().toString(), url.gueltigBis());
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
