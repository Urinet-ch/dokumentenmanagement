package ch.urinet.archiv.suche.adapter.in.rest;

import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.suche.application.DokumentSuche;
import ch.urinet.archiv.suche.application.HybridSuche;
import ch.urinet.archiv.suche.domain.AuszugTeil;
import ch.urinet.archiv.suche.domain.Fundstelle;
import ch.urinet.archiv.suche.domain.Hybridergebnis;
import ch.urinet.archiv.suche.domain.Hybridtreffer;
import ch.urinet.archiv.suche.domain.Richtung;
import ch.urinet.archiv.suche.domain.Sortierung;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import ch.urinet.archiv.suche.domain.Suchtreffer;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Volltextsuche mit Filtern über die sichtbaren Dokumente (A2–A4, Req 14.1–14.5, 14.7; Aufgabe 4.5). Die Parameter
 * kommen als Text und werden hier gelesen, damit jede ungültige Angabe 400 {@code ANFRAGE_UNGUELTIG} ergibt (nicht 404
 * aus der Umwandlung von JAX-RS). Die Suche wird nicht protokolliert (design.md «Protokoll und Manifest»); der
 * Suchbegriff erscheint weder in Fehlerantworten noch im Log (Req 16.7).
 * <p>
 * Modus (Spec 02, Req 2.7, Aufgabe 6.4): ohne {@code modus} oder mit {@code volltext} die Volltextsuche unverändert;
 * mit {@code hybrid} die {@link HybridSuche} mit Pflicht {@code q}, ohne {@code sortierung=DATUM}, {@code seite} und
 * {@code groesse}, höchstens {@value HybridSuche#PWA} Treffer ohne Blättern; {@code gesamt} ist dann die Zahl der
 * gelieferten Treffer. Die neuen Felder fehlen im Modus {@code volltext}, damit dessen Antwort gleich bleibt.
 */
@Path("/dokumente")
@Tag(name = "Dokumente")
@Produces(MediaType.APPLICATION_JSON)
public class SucheResource {

    private static final List<String> STATUS = Arrays.stream(DokumentStatus.values()).map(Enum::name).toList();

    static final String VOLLTEXT = "volltext";
    static final String HYBRID = "hybrid";

    private final DokumentSuche suche;
    private final HybridSuche hybrid;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public SucheResource(DokumentSuche suche, HybridSuche hybrid, MandantAufloesung aufloesung,
                         SecurityIdentity identitaet) {
        this.suche = suche;
        this.hybrid = hybrid;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @Schema(name = "Fundstelle", description = "Bester Abschnitt des Dokuments aus dem Vektor-Teil der Hybridsuche "
            + "(Spec 02, Req 2.2; Spec 03, Req 5.7)")
    public record FundstelleDto(@Schema(required = true, description = "Nummer des Abschnitts, ab 1") int abschnitt,
                                @Schema(nullable = true, description = "erste Seite; null ohne Seitentrenner")
                                Integer seiteVon,
                                @Schema(nullable = true, description = "letzte Seite; null ohne Seitentrenner")
                                Integer seiteBis) {
    }

    @Schema(name = "AuszugTeil", description = "Stück der Textstelle; treffer = hervorgehobener Suchbegriff (Req 14.4)")
    public record AuszugTeilDto(@Schema(required = true) String text, @Schema(required = true) boolean treffer) {
    }

    @Schema(name = "Suchtreffer", description = "Sichtbares Dokument in der Trefferliste")
    public record SuchtrefferDto(
            @Schema(required = true) UUID id,
            @Schema(nullable = true) String art,
            @Schema(nullable = true) String titel,
            @Schema(nullable = true) LocalDate datum,
            @Schema(required = true) Instant eingang,
            @Schema(required = true, enumeration = {"NEU", "VERARBEITUNG", "EINGANGSKORB", "ARCHIVIERUNG",
                    "ARCHIVIERUNG_FEHLER", "ARCHIVIERT", "FEHLER", "VERWORFEN"}) String status,
            @Schema(required = true, enumeration = {"NORMAL", "INTERN", "PERSONAL"},
                    description = "Ohne bestätigte Art gilt PERSONAL (Req 2.21)") String vertraulichkeit,
            @Schema(required = true) List<String> tags,
            @Schema(nullable = true) Integer geschaeftsjahr,
            @Schema(nullable = true) UUID partnerId,
            @Schema(nullable = true) String partnerName,
            @Schema(nullable = true) String belegnummer,
            @Schema(nullable = true, description = "Betrag; Währung in waehrung") BigDecimal betrag,
            @Schema(nullable = true) String waehrung,
            @Schema(nullable = true, enumeration = {"EINNAHME", "AUSGABE"}) String richtung,
            @Schema(required = true, description = "Textstelle mit Treffern; leer ohne Suchbegriff")
            List<AuszugTeilDto> auszug,
            @Schema(nullable = true, description = "Name der empfangenen Datei ohne Pfad, nur lesend; "
                    + "Herkunftsangabe, kein Titel (Req 14.9); null ohne Namen") String dateiname,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(nullable = true, description = "Nur im Modus hybrid: bester Abschnitt aus dem Vektor-Teil; fehlt "
                    + "bei Treffern nur aus dem Volltext und im Rückfall") FundstelleDto fundstelle) {
    }

    @Schema(name = "Suchergebnis")
    public record SuchergebnisDto(@Schema(required = true) List<SuchtrefferDto> treffer,
                                  @Schema(required = true, description = "Modus volltext: Zahl aller sichtbaren "
                                          + "Treffer. Modus hybrid: Zahl der gelieferten Treffer (höchstens 30), keine "
                                          + "Gesamtzahl") long gesamt,
                                  @Schema(required = true) int seite,
                                  @Schema(required = true) int groesse,
                                  @JsonInclude(JsonInclude.Include.NON_NULL)
                                  @Schema(enumeration = {VOLLTEXT, HYBRID}, description = "Nur im Modus hybrid "
                                          + "gesetzt") String modus,
                                  @JsonInclude(JsonInclude.Include.NON_NULL)
                                  @Schema(description = "Nur im Modus hybrid: true, wenn nur im Volltext gesucht "
                                          + "wurde (Rückfall, Spec 02, Req 2.3)") Boolean degradiert) {
    }

    @GET
    @ErfordertAktion(Aktion.A2)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Dokumente suchen",
            description = "Volltext (deutsche Stammformen; Titel, Partner, Tags, OCR-Text) mit Filtern; alle Filter "
                    + "mit «und», mehrere tag müssen alle stehen. Eine exakte Belegnummer als q steht zuerst. Nur "
                    + "sichtbare Dokumente, auch in gesamt (Req 2.3). Ohne status keine verworfenen Dokumente. "
                    + "Mit modus=hybrid Volltext und semantische Suche mit Reranker (Spec 02, Req 2.2, 2.7): q Pflicht, "
                    + "ohne sortierung=DATUM, seite und groesse; höchstens 30 Treffer ohne Blättern, degradiert bei "
                    + "Rückfall auf den Volltext. Ungültige Parameter → 400 ANFRAGE_UNGUELTIG.")
    public SuchergebnisDto suchen(
            @QueryParam("q") @Parameter(description = "Suchbegriff (Websuche-Syntax: \"Wortfolge\", -ohne, or), "
                    + "höchstens 200 Zeichen") String q,
            @QueryParam("art") @Parameter(description = "Code der Dokumentart") String art,
            @QueryParam("partner") @Parameter(description = "ID des Partners",
                    schema = @Schema(type = SchemaType.STRING, format = "uuid")) String partner,
            @QueryParam("datumVon") @Parameter(description = "Belegdatum ab",
                    schema = @Schema(type = SchemaType.STRING, format = "date")) String datumVon,
            @QueryParam("datumBis") @Parameter(description = "Belegdatum bis",
                    schema = @Schema(type = SchemaType.STRING, format = "date")) String datumBis,
            @QueryParam("betragVon") @Parameter(description = "Dezimalzahl mit Punkt, ohne Währungsumrechnung")
            String betragVon,
            @QueryParam("betragBis") @Parameter(description = "Dezimalzahl mit Punkt, ohne Währungsumrechnung")
            String betragBis,
            @QueryParam("richtung") @Parameter(schema = @Schema(type = SchemaType.STRING,
                    enumeration = {"EINNAHME", "AUSGABE"})) String richtung,
            @QueryParam("tag") @Parameter(description = "Tag; mehrfach möglich (höchstens 10), alle müssen stehen")
            List<String> tag,
            @QueryParam("status") @Parameter(schema = @Schema(type = SchemaType.STRING, enumeration = {"NEU",
                    "VERARBEITUNG", "EINGANGSKORB", "ARCHIVIERUNG", "ARCHIVIERUNG_FEHLER", "ARCHIVIERT", "FEHLER",
                    "VERWORFEN"})) String status,
            @QueryParam("geschaeftsjahr") @Parameter(schema = @Schema(type = SchemaType.INTEGER, format = "int32"))
            String geschaeftsjahr,
            @QueryParam("belegnummer") @Parameter(description = "exakte Belegnummer") String belegnummer,
            @QueryParam("sortierung") @Parameter(description = "Standard RELEVANZ mit q, sonst DATUM",
                    schema = @Schema(type = SchemaType.STRING, enumeration = {"RELEVANZ", "DATUM"})) String sortierung,
            @QueryParam("seite") @Parameter(description = "ab 0", schema = @Schema(type = SchemaType.INTEGER,
                    format = "int32", defaultValue = "0")) String seite,
            @QueryParam("groesse") @Parameter(description = "1–100", schema = @Schema(type = SchemaType.INTEGER,
                    format = "int32", defaultValue = "20")) String groesse,
            @QueryParam("modus") @Parameter(description = "Standard volltext; hybrid nur mit q, ohne sortierung=DATUM, "
                    + "seite und groesse (Spec 02, Req 2.7)", schema = @Schema(type = SchemaType.STRING,
                    enumeration = {VOLLTEXT, HYBRID})) String modus) {
        aufloesung.pruefen();
        if (status != null && !STATUS.contains(status)) {
            throw ungueltig();
        }
        if (modus != null && !VOLLTEXT.equals(modus) && !HYBRID.equals(modus)) {
            throw ungueltig();
        }
        boolean mitHybrid = HYBRID.equals(modus);
        if (mitHybrid && (seite != null || groesse != null)) {
            throw ungueltig();
        }
        Suchanfrage anfrage = new Suchanfrage(q, leer(art), lesen(partner, UUID::fromString),
                lesen(datumVon, LocalDate::parse), lesen(datumBis, LocalDate::parse),
                lesen(betragVon, BigDecimal::new), lesen(betragBis, BigDecimal::new),
                lesen(richtung, Richtung::valueOf), tag, leer(status), lesen(geschaeftsjahr, Integer::valueOf),
                leer(belegnummer), lesen(sortierung, Sortierung::valueOf),
                zahl(seite, 0), zahl(groesse, Suchanfrage.GROESSE_STANDARD));
        if (mitHybrid) {
            if (anfrage.begriff() == null || anfrage.sortierung() == Sortierung.DATUM) {
                throw ungueltig();
            }
            Hybridergebnis ergebnis = hybrid.suchen(benutzer(), anfrage);
            List<SuchtrefferDto> treffer = ergebnis.treffer().stream().map(SucheResource::dto).toList();
            return new SuchergebnisDto(treffer, treffer.size(), 0, HybridSuche.PWA, HYBRID, ergebnis.degradiert());
        }
        Suchergebnis ergebnis = suche.suchen(benutzer(), anfrage);
        return new SuchergebnisDto(ergebnis.treffer().stream().map(t -> dto(t, null)).toList(), ergebnis.gesamt(),
                anfrage.seite(), anfrage.groesse(), null, null);
    }

    /** Der ZugangFilter hat Rolle und Benutzer-ID schon geprüft (sonst 403). */
    private Benutzer benutzer() {
        return new Benutzer(identitaet.getPrincipal().getName(),
                Rolle.ausRealmRollen(identitaet.getRoles()).orElseThrow());
    }

    private static SuchtrefferDto dto(Hybridtreffer h) {
        Fundstelle f = h.fundstelle();
        return dto(h.treffer(), f == null ? null : new FundstelleDto(f.abschnitt(), f.seiteVon(), f.seiteBis()));
    }

    private static SuchtrefferDto dto(Suchtreffer t, FundstelleDto fundstelle) {
        return new SuchtrefferDto(t.id(), t.art(), t.titel(), t.datum(), t.eingang(), t.status(), t.vertraulichkeit(),
                t.tags(), t.geschaeftsjahr(), t.partnerId(), t.partnerName(), t.belegnummer(), t.betrag(),
                t.waehrung(), t.richtung(), t.auszug().stream().map(SucheResource::dto).toList(), t.dateiname(),
                fundstelle);
    }

    private static AuszugTeilDto dto(AuszugTeil teil) {
        return new AuszugTeilDto(teil.text(), teil.treffer());
    }

    private static String leer(String wert) {
        return wert == null || wert.isEmpty() ? null : wert;
    }

    private static <T> T lesen(String wert, Function<String, T> umwandlung) {
        if (leer(wert) == null) {
            return null;
        }
        try {
            return umwandlung.apply(wert);
        } catch (RuntimeException e) {
            throw ungueltig();
        }
    }

    private static int zahl(String wert, int standard) {
        Integer zahl = lesen(wert, Integer::valueOf);
        return zahl == null ? standard : zahl;
    }

    private static FachFehler ungueltig() {
        return new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }
}
