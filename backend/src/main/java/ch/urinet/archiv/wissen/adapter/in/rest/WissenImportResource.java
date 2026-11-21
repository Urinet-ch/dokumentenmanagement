package ch.urinet.archiv.wissen.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.PaketUngueltig;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenImport;
import ch.urinet.archiv.wissen.application.WissenPaketImport;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.net.URI;
import java.util.UUID;

@Path("/wissensentwuerfe")
@Tag(name = "Wissensbasis")
@Produces(MediaType.APPLICATION_JSON)
public class WissenImportResource {
    private final WissenPaketImport pakete;
    private final WissenImport importieren;
    private final MandantAufloesung mandant;
    private final SecurityIdentity identitaet;

    public WissenImportResource(WissenPaketImport pakete, WissenImport importieren, MandantAufloesung mandant,
                                SecurityIdentity identitaet) {
        this.pakete = pakete;
        this.importieren = importieren;
        this.mandant = mandant;
        this.identitaet = identitaet;
    }

    @POST
    @Path("/import")
    @ErfordertAktion(Aktion.A17)
    @RolesAllowed({"inhaber", "geschaeftsstelle"})
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Wissenspaket synchron als Entwurf importieren",
            description = "A17, nur OIDC. Keine KI, keine Freigabe. Höchstens 10 MiB ZIP und 60 Sekunden.")
    @APIResponse(responseCode = "201", description = "Importentwurf angelegt",
            content = @Content(schema = @Schema(implementation = WissenImport.Antwort.class)))
    @APIResponse(responseCode = "400", description = "Teil paket fehlt oder ist mehrfach vorhanden")
    @APIResponse(responseCode = "403", description = "Import nicht erlaubt")
    @APIResponse(responseCode = "409", description = "Paket bereits importiert oder zu viele offene Importe")
    @APIResponse(responseCode = "413", description = "Paket zu gross")
    @APIResponse(responseCode = "415", description = "Kein ZIP oder falscher Medientyp")
    @APIResponse(responseCode = "422", description = "Paket ungültig; Codes und Pfade ohne Inhalte")
    public Response importieren(@RestForm("paket")
                                @Parameter(description = "ZIP mit paket.yaml und Wissensseiten", required = true)
                                FileUpload paket,
                                @Parameter(hidden = true) MultipartFormDataInput formular,
                                @HeaderParam("X-Real-Ip") @Parameter(hidden = true) String realIp) throws IOException {
        var code = mandant.pruefen();
        long deadline = System.nanoTime() + pakete.zeitgrenzeNanos();
        if (formular == null || paket == null || formular.getValues().size() != 1) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        var teile = formular.getValues().get("paket");
        if (teile == null || teile.size() != 1 || !teile.iterator().next().isFileItem()) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var quelle = Files.newInputStream(paket.uploadedFile())) {
            byte[] puffer = new byte[8192];
            int gelesen;
            while ((gelesen = quelle.read(puffer)) != -1) {
                if (System.nanoTime() >= deadline) {
                    throw new PaketUngueltig("ZEIT", "paket.yaml");
                }
                if ((long) bytes.size() + gelesen > pakete.maxZipBytes()) {
                    throw new FachFehler(Fehlercode.DATEI_ZU_GROSS);
                }
                bytes.write(puffer, 0, gelesen);
            }
        }
        var antwort = pakete.importieren(benutzer(), code.wert(), bytes.toByteArray(), deadline, realIp);
        return Response.created(URI.create("/api/v1/wissensentwuerfe/" + antwort.entwurfId())).entity(antwort).build();
    }

    @POST
    @Path("/{id}/neu-pruefen")
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Quellen und Befunde eines Importentwurfs ohne KI neu prüfen")
    @APIResponse(responseCode = "204", description = "Prüfung erneuert; Änderungs-ETags erhöht")
    @APIResponse(responseCode = "403", description = "Prüfung nicht erlaubt")
    @APIResponse(responseCode = "404", description = "Importentwurf nicht gefunden")
    @APIResponse(responseCode = "409", description = "Entwurf ist kein offener Import")
    public Response neuPruefen(@PathParam("id") @Parameter(description = "ID des Importentwurfs") UUID id) {
        mandant.pruefen();
        importieren.neuPruefen(benutzer(), id);
        return Response.noContent().build();
    }

    private Benutzer benutzer() {
        return new Benutzer(identitaet.getPrincipal().getName(), Rolle.ausRealmRollen(identitaet.getRoles())
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_ERLAUBT)));
    }
}
