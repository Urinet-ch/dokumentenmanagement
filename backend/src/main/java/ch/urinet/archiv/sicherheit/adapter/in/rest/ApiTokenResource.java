package ch.urinet.archiv.sicherheit.adapter.in.rest;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.ApiClient;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * API-Tokens des eigenen Mandanten verwalten (A14, nur {@code inhaber}; Req 2.5, 2.20). Der Mandant kommt nur aus der
 * Anmeldung. Das Token steht einzig in der Antwort auf {@code POST} und wird danach nirgends mehr ausgegeben.
 */
@Path("/api-tokens")
@Tag(name = "API-Tokens")
@Produces(MediaType.APPLICATION_JSON)
public class ApiTokenResource {

    private final ApiTokenVerwaltung verwaltung;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public ApiTokenResource(ApiTokenVerwaltung verwaltung, MandantAufloesung aufloesung, SecurityIdentity identitaet) {
        this.verwaltung = verwaltung;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @Schema(name = "ApiTokenNeu")
    public record ApiTokenNeuDto(
            @Schema(required = true, maxLength = 100, description = "Bezeichnung, z. B. «Rechnungsgenerator»") String name,
            @Schema(required = true, enumeration = {"generator", "import"}) String scope) {
    }

    @Schema(name = "ApiToken")
    public record ApiTokenDto(String id, String name, @Schema(enumeration = {"generator", "import"}) String scope,
            boolean aktiv, Instant erstellt, @Schema(nullable = true) Instant zuletzt) {

        static ApiTokenDto aus(ApiClient client) {
            return new ApiTokenDto(client.id(), client.name(), client.scope().code(), client.aktiv(),
                    client.erstellt(), client.zuletzt().orElse(null));
        }
    }

    @Schema(name = "ApiTokenErstellt")
    public record ApiTokenErstelltDto(String id, String name,
            @Schema(enumeration = {"generator", "import"}) String scope, Instant erstellt,
            @Schema(description = "Das Token `ua_<id>_<geheimnis>`; wird nur dieses eine Mal ausgegeben.") String token) {
    }

    @GET
    @ErfordertAktion(Aktion.A14)
    @RolesAllowed("inhaber")
    @Operation(summary = "API-Tokens des Mandanten auflisten (ohne Geheimnis)")
    public List<ApiTokenDto> liste() {
        return verwaltung.liste(mandant()).stream().map(ApiTokenDto::aus).toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ErfordertAktion(Aktion.A14)
    @RolesAllowed("inhaber")
    @Operation(summary = "API-Token anlegen", description = "Das Token steht nur in dieser Antwort.")
    @APIResponse(responseCode = "201", description = "Angelegt",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ApiTokenErstelltDto.class)))
    public Response erstellen(ApiTokenNeuDto neu) {
        MandantCode mandant = mandant();
        ApiTokenVerwaltung.Erstellt erstellt = verwaltung.erstellen(mandant.wert(), neu == null ? null : neu.name(),
                neu == null ? null : neu.scope());
        ApiClient client = erstellt.client();
        return Response.status(Response.Status.CREATED)
                .header("Cache-Control", "no-store")
                .entity(new ApiTokenErstelltDto(client.id(), client.name(), client.scope().code(), client.erstellt(),
                        erstellt.token().text()))
                .build();
    }

    @DELETE
    @Path("/{tokenId}")
    @ErfordertAktion(Aktion.A14)
    @RolesAllowed("inhaber")
    @Operation(summary = "API-Token widerrufen", description = "Wirkt sofort; fremde oder unbekannte IDs → 404.")
    @APIResponse(responseCode = "204", description = "Widerrufen")
    public void widerrufen(@PathParam("tokenId") String tokenId) {
        verwaltung.widerrufen(mandant(), tokenId, Optional.of(identitaet.getPrincipal().getName()));
    }

    private MandantCode mandant() {
        return aufloesung.pruefen();
    }
}
