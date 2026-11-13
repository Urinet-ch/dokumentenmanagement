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
import ch.urinet.archiv.suche.domain.AuszugTeil;
import ch.urinet.archiv.suche.domain.Richtung;
import ch.urinet.archiv.suche.domain.Sortierung;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import ch.urinet.archiv.suche.domain.Suchtreffer;
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
 */
@Path("/dokumente")
@Tag(name = "Dokumente")
@Produces(MediaType.APPLICATION_JSON)
public class SucheResource {

    private static final List<String> STATUS = Arrays.stream(DokumentStatus.values()).map(Enum::name).toList();

    private final DokumentSuche suche;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public SucheResource(DokumentSuche suche, MandantAufloesung aufloesung, SecurityIdentity identitaet) {
        this.suche = suche;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
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
                    + "Herkunftsangabe, kein Titel (Req 14.9); null ohne Namen") String dateiname) {
    }

    @Schema(name = "Suchergebnis")
    public record SuchergebnisDto(@Schema(required = true) List<SuchtrefferDto> treffer,
                                  @Schema(required = true, description = "Zahl aller sichtbaren Treffer") long gesamt,
                                  @Schema(required = true) int seite,
                                  @Schema(required = true) int groesse) {
    }

    @GET
    @ErfordertAktion(Aktion.A2)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Dokumente suchen",
            description = "Volltext (deutsche Stammformen; Titel, Partner, Tags, OCR-Text) mit Filtern; alle Filter "
                    + "mit «und», mehrere tag müssen alle stehen. Eine exakte Belegnummer als q steht zuerst. Nur "
                    + "sichtbare Dokumente, auch in gesamt (Req 2.3). Ohne status keine verworfenen Dokumente. "
                    + "Ungültige Parameter → 400 ANFRAGE_UNGUELTIG.")
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
                    format = "int32", defaultValue = "20")) String groesse) {
        aufloesung.pruefen();
        if (status != null && !STATUS.contains(status)) {
            throw ungueltig();
        }
        Suchanfrage anfrage = new Suchanfrage(q, leer(art), lesen(partner, UUID::fromString),
                lesen(datumVon, LocalDate::parse), lesen(datumBis, LocalDate::parse),
                lesen(betragVon, BigDecimal::new), lesen(betragBis, BigDecimal::new),
                lesen(richtung, Richtung::valueOf), tag, leer(status), lesen(geschaeftsjahr, Integer::valueOf),
                leer(belegnummer), lesen(sortierung, Sortierung::valueOf),
                zahl(seite, 0), zahl(groesse, Suchanfrage.GROESSE_STANDARD));
        Suchergebnis ergebnis = suche.suchen(benutzer(), anfrage);
        return new SuchergebnisDto(ergebnis.treffer().stream().map(SucheResource::dto).toList(), ergebnis.gesamt(),
                anfrage.seite(), anfrage.groesse());
    }

    /** Der ZugangFilter hat Rolle und Benutzer-ID schon geprüft (sonst 403). */
    private Benutzer benutzer() {
        return new Benutzer(identitaet.getPrincipal().getName(),
                Rolle.ausRealmRollen(identitaet.getRoles()).orElseThrow());
    }

    private static SuchtrefferDto dto(Suchtreffer t) {
        return new SuchtrefferDto(t.id(), t.art(), t.titel(), t.datum(), t.eingang(), t.status(), t.vertraulichkeit(),
                t.tags(), t.geschaeftsjahr(), t.partnerId(), t.partnerName(), t.belegnummer(), t.betrag(),
                t.waehrung(), t.richtung(), t.auszug().stream().map(SucheResource::dto).toList(), t.dateiname());
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
