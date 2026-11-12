package ch.urinet.archiv.dokument.adapter.in.rest;

import ch.urinet.archiv.dokument.adapter.in.rest.DokumentResource.DokumentDto;
import ch.urinet.archiv.dokument.application.DokumentLesen;
import ch.urinet.archiv.dokument.application.Seite;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Eingangskorb lesen (A5, Req 2.3, 2.24): nur sichtbare Dokumente, mitarbeiter nur selbst erfasste; die Gesamtzahl
 * zählt mit demselben Filter. Eigene Klasse, weil {@code /dokumente} als Klassenpfad der {@link DokumentResource}
 * gehört.
 */
@Path("/eingangskorb")
@Tag(name = "Dokumente")
@Produces(MediaType.APPLICATION_JSON)
public class EingangskorbResource {

    private final DokumentLesen lesen;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;
    private final ObjectMapper json;

    public EingangskorbResource(DokumentLesen lesen, MandantAufloesung aufloesung, SecurityIdentity identitaet,
                                ObjectMapper json) {
        this.lesen = lesen;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
        this.json = json;
    }

    @Schema(name = "EingangskorbSeite")
    public record EingangskorbDto(@Schema(required = true) List<DokumentDto> eintraege,
                                  @Schema(required = true, description = "Zahl aller sichtbaren Einträge") long gesamt,
                                  @Schema(required = true) int seite,
                                  @Schema(required = true) int groesse) {
    }

    @GET
    @ErfordertAktion(Aktion.A5)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Eingangskorb lesen",
            description = "Dokumente in EINGANGSKORB, FEHLER und ARCHIVIERUNG_FEHLER, neueste zuerst; mitarbeiter "
                    + "sehen nur selbst erfasste (A5, Req 2.24). seite ab 0, groesse 1–100.")
    public EingangskorbDto eingangskorb(@QueryParam("seite") @DefaultValue("0") int seite,
                                        @QueryParam("groesse") @DefaultValue("50") int groesse) {
        aufloesung.pruefen();
        Benutzer benutzer = DokumentResource.benutzer(identitaet);
        Seite<Dokument> treffer = lesen.eingangskorb(benutzer, seite, groesse);
        return new EingangskorbDto(treffer.eintraege().stream()
                .map(d -> DokumentResource.dto(d, json, lesen.warnungen(benutzer, d), lesen.schwelle())).toList(),
                treffer.gesamt(), seite, groesse);
    }
}
