package ch.urinet.archiv.importe.adapter.in.rest;

import ch.urinet.archiv.importe.application.quelle.QuellenVerwaltung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.ErlaubtScope;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.ApiTokenScope;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Prüfsummen-Abfrage für Trockenläufe der Werkzeuge (Spec 09, 10.6): welche SHA-256 im Mandanten schon zu einem nicht
 * verworfenen Dokument gehören, ohne IDs (wie Spec 01, 3.4). Recht A1 wie der Upload, dazu der Scope {@code import};
 * es entsteht kein Dokument, darum kein Eintrag {@code ERFASSEN}.
 */
@Path("/dokumente/pruefsummen")
@Tag(name = "Quellen")
@Produces(MediaType.APPLICATION_JSON)
public class PruefsummenResource {

    private final QuellenVerwaltung verwaltung;

    public PruefsummenResource(QuellenVerwaltung verwaltung) {
        this.verwaltung = verwaltung;
    }

    @Schema(name = "PruefsummenAnfrage")
    public record AnfrageDto(@Schema(required = true, maxItems = QuellenVerwaltung.PRUEFSUMMEN_MAX,
            description = "SHA-256 als 64 Hex-Zeichen") List<String> sha256) {
    }

    @Schema(name = "PruefsummenAntwort")
    public record AntwortDto(@Schema(required = true, description = "die schon vorhandenen Prüfsummen, sortiert")
                             List<String> vorhanden) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A1)
    @ErlaubtScope(ApiTokenScope.IMPORT)
    @RolesAllowed({"inhaber", "mitarbeiter", "geschaeftsstelle", ApiTokenScope.ROLLE_IMPORT})
    @Operation(summary = "Prüfsummen abfragen", description = "Höchstens 1'000 Prüfsummen; Antwort ohne IDs (A1, "
            + "Scope import)")
    @APIResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = AntwortDto.class)))
    @APIResponse(responseCode = "400", description = "ANFRAGE_UNGUELTIG: mehr als 1'000 oder keine gültige SHA-256")
    public AntwortDto abfragen(AnfrageDto anfrage) {
        return new AntwortDto(verwaltung.pruefsummen(anfrage == null ? null : anfrage.sha256()).stream().sorted()
                .toList());
    }
}
