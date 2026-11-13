package ch.urinet.archiv.protokoll.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.application.ProtokollEinsicht;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollFilter;
import ch.urinet.archiv.protokoll.domain.ProtokollZeile;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Einsicht ins Zugriffsprotokoll und Benutzerverzeichnis des eigenen Mandanten (A13, nur Inhaber; Req 2.19, 10.5,
 * 10.10). Andere Rollen erhalten 403 und einen Eintrag {@code VERWEIGERT} (Req 10.7). Die Einsicht selbst ist eine Liste
 * ohne Dokumentbezug und wird nicht protokolliert (design.md «Protokoll und Manifest»). Parameter kommen als Text,
 * damit jede ungültige Angabe 400 {@code ANFRAGE_UNGUELTIG} ergibt. Die Aktionen nennt OpenAPI nur über
 * {@link ProtokollAktion}, damit Filter, Antwort und Enum nicht auseinanderlaufen.
 */
@Path("/protokoll")
@Tag(name = "Protokoll")
@Produces(MediaType.APPLICATION_JSON)
public class ProtokollResource {

    private final ProtokollEinsicht einsicht;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public ProtokollResource(ProtokollEinsicht einsicht, MandantAufloesung aufloesung, SecurityIdentity identitaet) {
        this.einsicht = einsicht;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @Schema(name = "ProtokollEintrag", description = "Eintrag des Zugriffsprotokolls; Name und Rolle aus dem "
            + "Benutzerverzeichnis zur Zeit des Eintrags (Req 10.10)")
    public record ProtokollEintragDto(
            @Schema(required = true) long id,
            @Schema(required = true) Instant zeit,
            @Schema(nullable = true, description = "Keycloak-Kennung (sub) oder token:<id>; leer bei Systemaktionen")
            String benutzer,
            @Schema(nullable = true) String name,
            @Schema(nullable = true, description = "INHABER, MITARBEITER, TREUHAENDER, GESCHAEFTSSTELLE oder API_TOKEN")
            String rolle,
            @Schema(required = true) ProtokollAktion aktion,
            @Schema(nullable = true) UUID dokumentId,
            @Schema(nullable = true) String ip,
            @Schema(nullable = true, description = "JSON-Objekt als Text, z. B. Fehlercode oder geänderte Felder")
            String details) {
    }

    @Schema(name = "ProtokollSeite")
    public record ProtokollSeiteDto(@Schema(required = true) List<ProtokollEintragDto> eintraege,
                                    @Schema(required = true, description = "Zahl aller Treffer des Filters") long gesamt,
                                    @Schema(required = true) int seite,
                                    @Schema(required = true) int groesse) {
    }

    @Schema(name = "VerzeichnisEintrag", description = "Zeile des Benutzerverzeichnisses (Req 10.10); gilt ab "
            + "gueltigVon bis gueltigBis oder bis zur nächsten Zeile derselben Kennung")
    public record VerzeichnisEintragDto(
            @Schema(required = true) long id,
            @Schema(required = true, description = "Zeitpunkt der Zeile") Instant zeit,
            @Schema(required = true) String benutzer,
            @Schema(nullable = true) String name,
            @Schema(nullable = true) String rolle,
            @Schema(required = true) Instant gueltigVon,
            @Schema(nullable = true) Instant gueltigBis,
            @Schema(required = true, enumeration = {"AKTION", "AUSTRITT", "WIDERRUF"}) String anlass,
            @Schema(nullable = true, description = "Kennung, die Austritt oder Widerruf erfasst hat") String erfasstVon) {
    }

    @GET
    @ErfordertAktion(Aktion.A13)
    @RolesAllowed("inhaber")
    @Operation(summary = "Zugriffsprotokoll durchsuchen",
            description = "Neueste zuerst; alle Filter mit «und». Ungültige Parameter → 400 ANFRAGE_UNGUELTIG.")
    public ProtokollSeiteDto suchen(
            @QueryParam("von") @Parameter(description = "Zeitpunkt ab (inklusive), ISO 8601 in UTC",
                    schema = @Schema(type = SchemaType.STRING, format = "date-time")) String von,
            @QueryParam("bis") @Parameter(description = "Zeitpunkt bis (exklusive), ISO 8601 in UTC",
                    schema = @Schema(type = SchemaType.STRING, format = "date-time")) String bis,
            @QueryParam("benutzer") @Parameter(description = "Kennung (sub oder token:<id>), höchstens 200 Zeichen")
            String benutzer,
            @QueryParam("dokument") @Parameter(description = "ID des Dokuments",
                    schema = @Schema(type = SchemaType.STRING, format = "uuid")) String dokument,
            @QueryParam("aktion") @Parameter(schema = @Schema(implementation = ProtokollAktion.class)) String aktion,
            @QueryParam("seite") @Parameter(description = "ab 0", schema = @Schema(type = SchemaType.INTEGER,
                    format = "int32", defaultValue = "0")) String seite,
            @QueryParam("groesse") @Parameter(description = "1–100", schema = @Schema(type = SchemaType.INTEGER,
                    format = "int32", defaultValue = "50")) String groesse) {
        aufloesung.pruefen();
        ProtokollFilter filter;
        try {
            filter = new ProtokollFilter(lesen(von, Instant::parse), lesen(bis, Instant::parse), lesen(benutzer, b -> b),
                    lesen(dokument, UUID::fromString), lesen(aktion, ProtokollAktion::valueOf), zahl(seite, 0),
                    zahl(groesse, ProtokollFilter.STANDARD_GROESSE));
        } catch (IllegalArgumentException e) {
            throw ungueltig();
        }
        ProtokollZeile.Seite treffer = einsicht.suchen(filter);
        return new ProtokollSeiteDto(treffer.eintraege().stream().map(ProtokollResource::dto).toList(),
                treffer.gesamt(), filter.seite(), filter.groesse());
    }

    @GET
    @Path("/benutzer")
    @ErfordertAktion(Aktion.A13)
    @RolesAllowed("inhaber")
    @Operation(summary = "Benutzerverzeichnis",
            description = "Alle Zeilen, nach Kennung und Reihenfolge des Eintrags; nur Einfügen, nie geändert.")
    public List<VerzeichnisEintragDto> verzeichnis() {
        aufloesung.pruefen();
        return einsicht.verzeichnis().stream().map(ProtokollResource::dto).toList();
    }

    @POST
    @Path("/benutzer/{benutzer}/austritt")
    @ErfordertAktion(Aktion.A13)
    @RolesAllowed("inhaber")
    @Operation(summary = "Austritt erfassen",
            description = "Beendet die Zuordnung der Kennung mit einer neuen Zeile (gültig bis jetzt). Unbekannte "
                    + "Kennung → 404; schon beendet oder API-Token (Widerruf über A14) → 409.")
    @APIResponse(responseCode = "204", description = "Austritt erfasst")
    public void austritt(@PathParam("benutzer") String benutzer) {
        aufloesung.pruefen();
        einsicht.austritt(benutzer, Optional.of(identitaet.getPrincipal().getName()));
    }

    private static ProtokollEintragDto dto(ProtokollZeile z) {
        return new ProtokollEintragDto(z.id(), z.zeit(), z.benutzer().orElse(null), z.name().orElse(null),
                z.rolle().orElse(null), ProtokollAktion.valueOf(z.aktion()), z.dokumentId().orElse(null), z.ip().orElse(null),
                z.details().orElse(null));
    }

    private static VerzeichnisEintragDto dto(VerzeichnisEintrag e) {
        return new VerzeichnisEintragDto(e.id(), e.zeit(), e.benutzer(), e.name().orElse(null),
                e.rolle().orElse(null), e.gueltigVon(), e.gueltigBis().orElse(null), e.anlass().name(),
                e.erfasstVon().orElse(null));
    }

    private static <T> Optional<T> lesen(String wert, Function<String, T> umwandlung) {
        if (wert == null || wert.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(umwandlung.apply(wert));
        } catch (RuntimeException e) {
            throw ungueltig();
        }
    }

    private static int zahl(String wert, int standard) {
        return lesen(wert, Integer::valueOf).orElse(standard);
    }

    private static FachFehler ungueltig() {
        return new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }
}
