package ch.urinet.archiv.wissen.adapter.in.rest;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenEinstellungen;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/wissensbasis/einstellungen")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Wissensbasis")
public class WissenEinstellungenResource {
    private final WissenEinstellungen einstellungen;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public WissenEinstellungenResource(WissenEinstellungen einstellungen, MandantAufloesung aufloesung,
                                       SecurityIdentity identitaet) {
        this.einstellungen = einstellungen;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @GET
    @ErfordertAktion(Aktion.A19)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Wissenseinstellungen lesen",
            description = "Nur Inhaber (A19); null bedeutet den konfigurierten Startwert.")
    public WissenEinstellungen.Werte lesen() {
        aufloesung.pruefen();
        return einstellungen.lesen(benutzer());
    }

    @PUT
    @ErfordertAktion(Aktion.A19)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Wissenseinstellungen ersetzen",
            description = "Nur Inhaber (A19); null setzt den jeweiligen Startwert ein.")
    public WissenEinstellungen.Werte speichern(WissenEinstellungen.Werte werte) {
        aufloesung.pruefen();
        return einstellungen.speichern(benutzer(), werte);
    }

    private Benutzer benutzer() {
        return new Benutzer(identitaet.getPrincipal().getName(), Rolle.ausRealmRollen(identitaet.getRoles())
                .orElseThrow());
    }
}
