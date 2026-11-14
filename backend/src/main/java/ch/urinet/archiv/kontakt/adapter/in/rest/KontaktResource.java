package ch.urinet.archiv.kontakt.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.kontakt.application.KontaktSeite;
import ch.urinet.archiv.kontakt.application.KontaktSuche;
import ch.urinet.archiv.kontakt.application.KontaktUebernahme;
import ch.urinet.archiv.kontakt.application.KontaktVerwaltung;
import ch.urinet.archiv.kontakt.application.KontaktVerweise;
import ch.urinet.archiv.kontakt.application.KontaktZusammenfuehren;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import ch.urinet.archiv.kontakt.domain.KontaktTyp;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
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

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kontakte des eigenen Mandanten (design.md «Kontakt und Importe», «Kontakte ansehen und pflegen»): suchen für die
 * Partnerauswahl, auflisten und ansehen (A6, Req 17.1–17.3) und von Hand pflegen, zusammenführen, als inaktiv markieren
 * und wieder aktivieren (A12, nur {@code inhaber}; Req 2.18, 17.6–17.16). API-Tokens haben keinen Zugriff (kein
 * {@code @ErlaubtScope}). Suche, Liste und Detail protokollieren nicht einzeln (ohne Dokumentbezug, design.md
 * «Protokoll und Manifest»). Kein {@code DELETE}: Kontakte werden nie gelöscht (Req 17.11). Fehler nennen nie Werte.
 */
@Path("/kontakte")
@Tag(name = "Kontakte")
@Produces(MediaType.APPLICATION_JSON)
public class KontaktResource {

    // nur ein starkes ETag passt (RFC 9110, If-Match vergleicht stark)
    private static final Pattern VERSION = Pattern.compile("^\"(\\d{1,9})\"$");
    private static final int TEXT_MAX = 200;
    private static final int PLZ_MAX = 20;
    private static final int TELEFON_MAX = 50;
    private static final int UID_MAX = 30;
    private static final int IBAN_MAX = 40;
    private static final int KATEGORIE_MAX = 100;

    private static final int SUCHE_MAX = 200;
    private static final int GRUND_MAX = 1000;
    private static final int GROESSE_MAX = 100;
    private static final int GROESSE_STANDARD = 50;
    private static final Set<String> QUELLEN = Set.of(KontaktUebernahme.QUELLE_CSV_IMPORT, Kontakt.QUELLE_MANUELL);
    private static final String ALLE_ROLLEN_A6 = "Recht A6 (inhaber, geschaeftsstelle); sonst 403 NICHT_ERLAUBT";

    private final KontaktVerwaltung verwaltung;
    private final KontaktZusammenfuehren zusammenfuehren;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;
    private final ObjectReader leser;

    public KontaktResource(KontaktVerwaltung verwaltung, KontaktZusammenfuehren zusammenfuehren,
                           MandantAufloesung aufloesung, SecurityIdentity identitaet, ObjectMapper mapper) {
        this.verwaltung = verwaltung;
        this.zusammenfuehren = zusammenfuehren;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
        // unbekannte Felder, Zeichen nach dem JSON-Wert und null für Zahlen lehnt die Resource ab (400)
        this.leser = mapper.reader().with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    @Schema(name = "KontaktAngaben", description = "Bei PATCH bleibt ein fehlendes Feld unverändert, ein leerer Text "
            + "löscht es; Name und Typ lassen sich nicht leeren. Ein Wert über der Höchstlänge (ohne Leerraum am Rand) "
            + "→ 400.")
    public record KontaktAngabenDto(
            @Schema(enumeration = {"ORGANISATION", "PERSON"}, description = "Pflicht beim Anlegen") String typ,
            @Schema(maxLength = TEXT_MAX, description = "Firmen- bzw. Nachname; Pflicht beim Anlegen") String name,
            @Schema(maxLength = TEXT_MAX) String vorname, @Schema(maxLength = TEXT_MAX) String strasse,
            @Schema(maxLength = PLZ_MAX) String plz, @Schema(maxLength = TEXT_MAX) String ort,
            @Schema(maxLength = TEXT_MAX) String email, @Schema(maxLength = TELEFON_MAX) String telefon,
            @Schema(maxLength = UID_MAX, description = "UID, z. B. «CHE-123.456.789 MWST»") String uid,
            @Schema(maxLength = IBAN_MAX) String iban, @Schema(maxLength = KATEGORIE_MAX) String kategorie) {

        /** Prüft die Höchstlängen aus OpenAPI (in Zeichen, ohne Leerraum am Rand); zu lang → 400. */
        KontaktAngaben angaben() {
            hoechstens(name, TEXT_MAX);
            hoechstens(vorname, TEXT_MAX);
            hoechstens(strasse, TEXT_MAX);
            hoechstens(plz, PLZ_MAX);
            hoechstens(ort, TEXT_MAX);
            hoechstens(email, TEXT_MAX);
            hoechstens(telefon, TELEFON_MAX);
            hoechstens(uid, UID_MAX);
            hoechstens(iban, IBAN_MAX);
            hoechstens(kategorie, KATEGORIE_MAX);
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
            @Schema(nullable = true) String kategorie,
            @Schema(enumeration = {"MANUELL", "CSV_IMPORT"}) String quelle,
            boolean nichtImExport, Instant erstellt, Instant geaendert,
            @Schema(description = "Version für If-Match (auch als ETag)") int version,
            @Schema(description = "false: inaktiv markiert oder zusammengeführt (Req 17.11)") boolean aktiv,
            @Schema(nullable = true, description = "seit wann inaktiv (UTC)") Instant inaktivSeit,
            @Schema(nullable = true, description = "Kontakt, in den dieser zusammengeführt wurde")
            String zusammengefuehrtIn) {

        static KontaktDto aus(Kontakt k) {
            return new KontaktDto(k.id().toString(), k.crmId(), k.typ().name(), k.name(), k.vorname(), k.strasse(),
                    k.plz(), k.ort(), k.email(), k.telefon(), k.uid(), k.iban(), k.kategorie(), k.quelle(),
                    k.nichtImExport(), k.erstellt(), k.geaendert(), k.version(), k.aktiv(), k.inaktivSeit(),
                    k.zusammengefuehrtIn() == null ? null : k.zusammengefuehrtIn().toString());
        }
    }

    @Schema(name = "KontaktListe", description = "Eine Seite der Kontaktliste mit der Gesamtzahl der Treffer")
    public record KontaktListeDto(long gesamt, int seite, int groesse, List<KontaktEintragDto> eintraege) {
    }

    @Schema(name = "KontaktEintrag", description = "Ein Kontakt in der Liste (Req 17.1)")
    public record KontaktEintragDto(String id, @Schema(enumeration = {"ORGANISATION", "PERSON"}) String typ,
            String name, @Schema(nullable = true) String vorname, @Schema(nullable = true) String plz,
            @Schema(nullable = true) String ort, @Schema(nullable = true) String email,
            @Schema(enumeration = {"MANUELL", "CSV_IMPORT"}) String quelle, boolean aktiv, boolean nichtImExport) {

        static KontaktEintragDto aus(Kontakt k) {
            return new KontaktEintragDto(k.id().toString(), k.typ().name(), k.name(), k.vorname(), k.plz(), k.ort(),
                    k.email(), k.quelle(), k.aktiv(), k.nichtImExport());
        }
    }

    @Schema(name = "KontaktVerweis", description = "ID und Name eines anderen Kontakts")
    public record KontaktVerweisDto(String id, String name) {

        static KontaktVerweisDto aus(Kontakt k) {
            return new KontaktVerweisDto(k.id().toString(), k.name());
        }
    }

    @Schema(name = "KontaktDetail", description = "Stammdaten und Status eines Kontakts (Req 17.3), ohne Zahl der "
            + "Dokumente; die Dokumente liefert GET /dokumente?partner=<id>&sortierung=DATUM")
    public record KontaktDetailDto(String id, @Schema(nullable = true) String crmId,
            @Schema(enumeration = {"ORGANISATION", "PERSON"}) String typ, String name,
            @Schema(nullable = true) String vorname, @Schema(nullable = true) String strasse,
            @Schema(nullable = true) String plz, @Schema(nullable = true) String ort,
            @Schema(nullable = true) String email, @Schema(nullable = true) String telefon,
            @Schema(nullable = true) String uid, @Schema(nullable = true) String iban,
            @Schema(nullable = true) String kategorie,
            @Schema(enumeration = {"MANUELL", "CSV_IMPORT"}) String quelle,
            @Schema(description = "«nicht mehr im CRM-Export» (Req 13.7)") boolean nichtImExport, boolean aktiv,
            @Schema(nullable = true, description = "seit wann inaktiv (UTC)") Instant inaktivSeit,
            @Schema(nullable = true, description = "Ziel, falls dieser Kontakt zusammengeführt wurde")
            KontaktVerweisDto zusammengefuehrtIn,
            @Schema(description = "Kontakte, die in diesen zusammengeführt wurden; höchstens 50, die neuesten zuerst")
            List<KontaktVerweisDto> zusammengefuehrtAus,
            Instant erstellt, Instant geaendert,
            @Schema(description = "Version für If-Match (auch als ETag)") int version) {

        static KontaktDetailDto aus(KontaktVerwaltung.Detail d) {
            Kontakt k = d.kontakt();
            return new KontaktDetailDto(k.id().toString(), k.crmId(), k.typ().name(), k.name(), k.vorname(),
                    k.strasse(), k.plz(), k.ort(), k.email(), k.telefon(), k.uid(), k.iban(), k.kategorie(), k.quelle(),
                    k.nichtImExport(), k.aktiv(), k.inaktivSeit(),
                    d.zusammengefuehrtIn().map(KontaktVerweisDto::aus).orElse(null),
                    d.zusammengefuehrtAus().stream().map(KontaktVerweisDto::aus).toList(), k.erstellt(), k.geaendert(),
                    k.version());
        }
    }

    @Schema(name = "KontaktVerweise", description = "Zahl der Verweise über alle Dokumente des Mandanten (Req 17.17)")
    public record VerweiseDto(int dokumente, int verkaufschancen, int korrekturbeispiele) {
    }

    @Schema(name = "KontaktZusammenfuehren", description = "Ziel und Grund; unbekannte Felder → 400")
    public record ZusammenfuehrenDto(
            @Schema(required = true, format = "uuid", description = "aktiver Kontakt, in den die Quelle wandert")
            UUID ziel,
            @Schema(required = true, minLength = 1, maxLength = GRUND_MAX, description = "Pflicht (Req 17.8)")
            String grund) {
    }

    @Schema(name = "KontaktZusammengefuehrt", description = "Ziel und Zahl der umgehängten Verweise (Req 17.7)")
    public record ZusammengefuehrtDto(String ziel, int dokumente, int verkaufschancen, int korrekturbeispiele) {
    }

    @Schema(name = "KontaktInaktiv", description = "Grund (optional); unbekannte Felder → 400")
    public record InaktivDto(@Schema(nullable = true, maxLength = GRUND_MAX) String grund) {
    }

    @GET
    @ErfordertAktion(Aktion.A6)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Kontakte suchen (Partnerauswahl)",
            description = "Ähnliche oder enthaltene Namen, die besten zuerst; höchstens 20 Treffer; nur aktive "
                    + "Kontakte (Req 17.12).")
    public List<KontaktDto> suchen(@QueryParam("q") @Parameter(description = "Suchtext (Name)") String q) {
        aufloesung.pruefen();
        return verwaltung.suchen(q).stream().map(KontaktDto::aus).toList();
    }

    @GET
    @Path("/liste")
    @ErfordertAktion(Aktion.A6)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Kontakte auflisten (Req 17.1, 17.2)", description = "Mit q: Name enthalten oder ähnlich, "
            + "Vorname oder Ort enthalten (ohne Gross-/Kleinschreibung), E-Mail enthalten, PLZ oder Kontakt-ID aus "
            + "21.CRM gleich; exakte Kontakt-ID und E-Mail zuerst, dann Ähnlichkeit, dann Name. Ohne q nach Name, "
            + "Vorname. " + ALLE_ROLLEN_A6 + ". Ungültige Parameter → 400 ANFRAGE_UNGUELTIG.")
    @APIResponse(responseCode = "200", description = "Eine Seite",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = KontaktListeDto.class)))
    @APIResponse(responseCode = "400", description = "ANFRAGE_UNGUELTIG: q, status, quelle, nichtImExport, seite "
            + "oder groesse ungültig")
    @APIResponse(responseCode = "403", description = "NICHT_ERLAUBT: ohne Recht A6")
    public KontaktListeDto liste(
            @QueryParam("q") @Parameter(description = "Suchbegriff, 1–200 Zeichen ohne Steuerzeichen") String q,
            @QueryParam("status") @Parameter(description = "Standard AKTIV", schema = @Schema(
                    type = SchemaType.STRING, enumeration = {"AKTIV", "INAKTIV", "ALLE"})) String status,
            @QueryParam("quelle") @Parameter(schema = @Schema(type = SchemaType.STRING,
                    enumeration = {"CSV_IMPORT", "MANUELL"})) String quelle,
            @QueryParam("nichtImExport") @Parameter(schema = @Schema(type = SchemaType.BOOLEAN)) String nichtImExport,
            @QueryParam("seite") @Parameter(description = "ab 0", schema = @Schema(type = SchemaType.INTEGER,
                    format = "int32", minimum = "0", defaultValue = "0")) String seite,
            @QueryParam("groesse") @Parameter(description = "1–100", schema = @Schema(type = SchemaType.INTEGER,
                    format = "int32", minimum = "1", maximum = "100", defaultValue = "50")) String groesse) {
        aufloesung.pruefen();
        String text = suchbegriff(q);
        KontaktSuche.Status s = status == null ? KontaktSuche.Status.AKTIV : wert(status, KontaktSuche.Status.class);
        if (quelle != null && !QUELLEN.contains(quelle)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Boolean ohneExport = nichtImExport == null ? null : wahrheitswert(nichtImExport);
        int nr = zahl(seite, 0, 0, Integer.MAX_VALUE);
        int anzahl = zahl(groesse, GROESSE_STANDARD, 1, GROESSE_MAX);
        KontaktSeite treffer = verwaltung.liste(text, s, quelle, ohneExport, nr, anzahl);
        return new KontaktListeDto(treffer.gesamt(), nr, anzahl,
                treffer.eintraege().stream().map(KontaktEintragDto::aus).toList());
    }

    @GET
    @Path("/{kontaktId}")
    @ErfordertAktion(Aktion.A6)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Kontakt ansehen (Req 17.3)", description = "Auch inaktive Kontakte. " + ALLE_ROLLEN_A6
            + "; unbekannt oder anderer Mandant → 404 NICHT_GEFUNDEN.")
    @APIResponse(responseCode = "200", description = "Kontakt",
            headers = @Header(name = "ETag", description = "Version des Kontakts für If-Match"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = KontaktDetailDto.class)))
    @APIResponse(responseCode = "403", description = "NICHT_ERLAUBT: ohne Recht A6")
    @APIResponse(responseCode = "404", description = "NICHT_GEFUNDEN")
    public Response detail(@PathParam("kontaktId") String kontaktId) {
        aufloesung.pruefen();
        KontaktVerwaltung.Detail detail = verwaltung.detail(id(kontaktId));
        return Response.ok(KontaktDetailDto.aus(detail))
                .tag(new EntityTag(Integer.toString(detail.kontakt().version()))).build();
    }

    @GET
    @Path("/{kontaktId}/verweise")
    @ErfordertAktion(Aktion.A12)
    @RolesAllowed("inhaber")
    @Operation(summary = "Verweise auf einen Kontakt zählen (Req 17.17)", description = "Über alle Dokumente des "
            + "Mandanten; nur Recht A12 (inhaber), sonst 403. Unbekannt → 404.")
    @APIResponse(responseCode = "200", description = "Zahlen", content = @Content(
            mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = VerweiseDto.class)))
    @APIResponse(responseCode = "403", description = "NICHT_ERLAUBT: ohne Recht A12")
    @APIResponse(responseCode = "404", description = "NICHT_GEFUNDEN")
    public VerweiseDto verweise(@PathParam("kontaktId") String kontaktId) {
        aufloesung.pruefen();
        KontaktVerweise.Zahlen z = verwaltung.verweise(id(kontaktId));
        return new VerweiseDto(z.dokumente(), z.verkaufschancen(), z.korrekturbeispiele());
    }

    @POST
    @Path("/{kontaktId}/zusammenfuehren")
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A12)
    @RolesAllowed("inhaber")
    @Operation(summary = "Kontakt in einen anderen zusammenführen (Req 17.7–17.10)", description = "Eine "
            + "Transaktion: Dokumente (neue Version, bei archivierten eine Fassung in dokument_version), Partner des "
            + "KI-Vorschlags, Verkaufschancen und Korrekturbeispiele wandern zum Ziel, die Quelle wird inaktiv mit "
            + "Verweis auf das Ziel; Protokoll KONTAKT_ZUSAMMENFUEHREN. Nicht umkehrbar. If-Match der Quelle: fehlt "
            + "→ 428, nur ein starkes ETag passt, sonst oder veraltet → 412. Körper ungültig, Ziel gleich Quelle oder "
            + "unbekannt → 400; Quelle unbekannt → 404; Quelle oder Ziel inaktiv, oder eine laufende Übernahme hält "
            + "einen der Kontakte → 409. In allen Fehlerfällen ändert sich nichts.")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = ZusammenfuehrenDto.class)))
    @APIResponse(responseCode = "200", description = "Zusammengeführt",
            headers = @Header(name = "ETag", description = "Neue Version der Quelle"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ZusammengefuehrtDto.class)))
    @APIResponse(responseCode = "400", description = "ANFRAGE_UNGUELTIG")
    @APIResponse(responseCode = "403", description = "NICHT_ERLAUBT: ohne Recht A12")
    @APIResponse(responseCode = "404", description = "NICHT_GEFUNDEN: Quelle unbekannt")
    @APIResponse(responseCode = "409", description = "STATUS_KONFLIKT")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response zusammenfuehren(@PathParam("kontaktId") String kontaktId,
                                    @HeaderParam(HttpHeaders.IF_MATCH) @Parameter(required = true) String ifMatch,
                                    String inhalt) {
        aufloesung.pruefen();
        UUID id = id(kontaktId);
        int version = version(ifMatch);
        ZusammenfuehrenDto anfrage = lesen(inhalt, ZusammenfuehrenDto.class, false);
        String grund = grund(anfrage.grund(), true);
        if (anfrage.ziel() == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        KontaktZusammenfuehren.Ergebnis e = zusammenfuehren.zusammenfuehren(identitaet.getPrincipal().getName(), id,
                version, anfrage.ziel(), grund);
        return Response.ok(new ZusammengefuehrtDto(e.ziel().toString(), e.zahlen().dokumente(),
                        e.zahlen().verkaufschancen(), e.zahlen().korrekturbeispiele()))
                .tag(new EntityTag(Integer.toString(e.quelle().version()))).build();
    }

    @POST
    @Path("/{kontaktId}/inaktiv")
    @ErfordertAktion(Aktion.A12)
    @RolesAllowed("inhaber")
    @Operation(summary = "Kontakt als inaktiv markieren (Req 17.11)", description = "Stammdaten und Zuordnungen "
            + "bleiben; der Kontakt ist kein Ziel neuer Zuordnungen mehr (Req 17.12). Protokoll KONTAKT_INAKTIV. "
            + "If-Match wie bei PATCH (428/412); schon inaktiv → 409; Körper ungültig → 400.")
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = InaktivDto.class)))
    @APIResponse(responseCode = "200", description = "Inaktiv",
            headers = @Header(name = "ETag", description = "Neue Version des Kontakts"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = KontaktDto.class)))
    @APIResponse(responseCode = "409", description = "STATUS_KONFLIKT: schon inaktiv")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response inaktiv(@PathParam("kontaktId") String kontaktId,
                            @HeaderParam(HttpHeaders.IF_MATCH) @Parameter(required = true) String ifMatch,
                            String inhalt) {
        aufloesung.pruefen();
        UUID id = id(kontaktId);
        int version = version(ifMatch);
        InaktivDto anfrage = lesen(inhalt, InaktivDto.class, true);
        return antwort(Response.ok(), verwaltung.inaktivSetzen(id, version,
                anfrage == null ? null : grund(anfrage.grund(), false)));
    }

    @POST
    @Path("/{kontaktId}/aktiv")
    @ErfordertAktion(Aktion.A12)
    @RolesAllowed("inhaber")
    @Operation(summary = "Kontakt wieder aktivieren (Req 17.15)", description = "Nur ein inaktiv markierter, nicht "
            + "zusammengeführter Kontakt; sonst 409. Protokoll KONTAKT_AKTIV. If-Match wie bei PATCH (428/412).")
    @APIResponse(responseCode = "200", description = "Wieder aktiv",
            headers = @Header(name = "ETag", description = "Neue Version des Kontakts"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = KontaktDto.class)))
    @APIResponse(responseCode = "409", description = "STATUS_KONFLIKT: aktiv oder zusammengeführt")
    @APIResponse(responseCode = "412", description = "VERSION_KONFLIKT")
    @APIResponse(responseCode = "428", description = "VERSION_FEHLT")
    public Response aktiv(@PathParam("kontaktId") String kontaktId,
                          @HeaderParam(HttpHeaders.IF_MATCH) @Parameter(required = true) String ifMatch) {
        aufloesung.pruefen();
        UUID id = id(kontaktId);
        return antwort(Response.ok(), verwaltung.aktivSetzen(id, version(ifMatch)));
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
            + "IDs → 404, fehlendes If-Match → 428, inaktiver Kontakt → 409 STATUS_KONFLIKT (Req 17.16), veraltete "
            + "Version → 412.")
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

    /** Leer oder nur Leerraum heisst ohne Suchbegriff; über 200 Zeichen oder mit Steuerzeichen → 400. */
    static String suchbegriff(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        if (q.codePointCount(0, q.length()) > SUCHE_MAX
                || q.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return q;
    }

    static int zahl(String text, int standard, int min, int max) {
        if (text == null) {
            return standard;
        }
        try {
            int wert = Integer.parseInt(text);
            if (wert < min || wert > max) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            return wert;
        } catch (NumberFormatException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    private static boolean wahrheitswert(String text) {
        return switch (text) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        };
    }

    private static <E extends Enum<E>> E wert(String text, Class<E> art) {
        try {
            return Enum.valueOf(art, text);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    /** Bereinigter Grund; Pflicht: fehlt oder leer → 400; über 1000 Zeichen → 400. Ohne Pflicht: leer → {@code null}. */
    static String grund(String grund, boolean pflicht) {
        String bereinigt = grund == null ? "" : grund.strip();
        if (bereinigt.isEmpty()) {
            if (pflicht) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            return null;
        }
        if (bereinigt.codePointCount(0, bereinigt.length()) > GRUND_MAX) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return bereinigt;
    }

    /** Liest den Körper streng; ungültig → 400. Ohne Körper {@code null}, wenn {@code leerErlaubt}, sonst 400. */
    private <T> T lesen(String inhalt, Class<T> art, boolean leerErlaubt) {
        if (inhalt == null || inhalt.isBlank()) {
            if (leerErlaubt) {
                return null;
            }
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        try {
            T wert = leser.forType(art).readValue(inhalt);
            if (wert == null) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            return wert;
        } catch (JacksonException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    private static void hoechstens(String wert, int max) {
        if (wert != null) {
            String bereinigt = wert.strip();
            if (bereinigt.codePointCount(0, bereinigt.length()) > max) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
        }
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
