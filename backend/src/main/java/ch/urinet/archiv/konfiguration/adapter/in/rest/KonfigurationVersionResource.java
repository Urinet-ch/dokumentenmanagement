package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.konfiguration.application.KonfigurationVerwaltung;
import ch.urinet.archiv.konfiguration.domain.KonfigurationsVersion;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.List;

@Path("/konfiguration/versionen")
@Tag(name = "Konfiguration")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("inhaber")
public class KonfigurationVersionResource {

    private final KonfigurationVerwaltung verwaltung;
    private final MandantAufloesung aufloesung;

    public KonfigurationVersionResource(KonfigurationVerwaltung verwaltung, MandantAufloesung aufloesung) {
        this.verwaltung = verwaltung;
        this.aufloesung = aufloesung;
    }

    @Schema(name = "KonfigurationsVersion")
    public record VersionDto(
            @Schema(required = true, minimum = "1") int version,
            @Schema(required = true) Instant zeit,
            @Schema(required = true) String wer,
            @Schema(nullable = true) String grund,
            @Schema(required = true, enumeration = {"APP", "DATEI", "SEED", "KOMMANDO", "MIGRATION"}) String quelle,
            @Schema(required = true, pattern = "^[0-9a-f]{64}$") String sha256) {

        static VersionDto aus(KonfigurationsVersion version) {
            return new VersionDto(version.version(), version.zeit(), version.wer(), version.grund(),
                    version.quelle().name(), version.sha256());
        }
    }

    @Schema(name = "KonfigurationsVersionDetail")
    public record VersionDetailDto(
            @Schema(required = true) VersionDto version,
            @Schema(required = true, description = "Unveränderliches Konfigurationsabbild") Object inhalt) {

        static VersionDetailDto aus(KonfigurationsVersion version) {
            return new VersionDetailDto(VersionDto.aus(version), version.inhalt());
        }
    }

    @GET
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Konfigurationsversionen auflisten", description = "Nur mit Recht A11.")
    @APIResponse(responseCode = "200", description = "Versionen")
    @APIResponse(responseCode = "403", description = "NICHT_ERLAUBT")
    public Response liste() {
        aufloesung.pruefen();
        List<VersionDto> versionen = verwaltung.versionen().stream().map(VersionDto::aus).toList();
        return Response.ok(versionen)
                .tag(new EntityTag(Integer.toString(verwaltung.aktuelleVersion()))).build();
    }

    @GET
    @Path("/{nummer}")
    @ErfordertAktion(Aktion.A11)
    @RolesAllowed("inhaber")
    @Operation(summary = "Konfigurationsversion ansehen", description = "Das Abbild bleibt unverändert lesbar.")
    @APIResponse(responseCode = "200", description = "Version und vollständiges Abbild")
    @APIResponse(responseCode = "404", description = "NICHT_GEFUNDEN")
    @APIResponse(responseCode = "403", description = "NICHT_ERLAUBT")
    public Response version(@PathParam("nummer") int nummer) {
        aufloesung.pruefen();
        if (nummer < 1) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        KonfigurationsVersion version = verwaltung.version(nummer);
        return Response.ok(VersionDetailDto.aus(version))
                .tag(new EntityTag(Integer.toString(version.version()))).build();
    }
}
