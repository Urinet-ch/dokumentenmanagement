package ch.urinet.archiv.wissen.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.wissen.application.WissensquelleMarkieren;
import ch.urinet.archiv.wissen.application.WissensquelleMarkierungPort;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/wissen/quellen")
@Tag(name = "Wissensbasis")
@Produces(MediaType.APPLICATION_JSON)
public class WissensquellenResource {
    private final WissensquelleMarkieren markieren;
    private final MandantAufloesung mandant;
    private final SecurityIdentity identitaet;

    public WissensquellenResource(WissensquelleMarkieren markieren, MandantAufloesung mandant,
                                  SecurityIdentity identitaet) {
        this.markieren = markieren;
        this.mandant = mandant;
        this.identitaet = identitaet;
    }

    @GET
    @ErfordertAktion(Aktion.A15)
    @RolesAllowed({"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"})
    @Protokolliert(aktion = ProtokollAktion.WISSEN_ANZEIGEN)
    @Operation(summary = "Sichtbare Wissensquellen auflisten")
    public List<WissensquelleMarkierungPort.Quelle> quellen() {
        mandant.pruefen();
        Rolle rolle = Rolle.ausRealmRollen(identitaet.getRoles())
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_ERLAUBT));
        return markieren.quellen(Sichtregel.fuer(new Benutzer(identitaet.getPrincipal().getName(), rolle)));
    }
}
