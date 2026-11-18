package ch.urinet.archiv.ki.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.application.chat.VollstaendigkeitAbfrage;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontext;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.ZoneId;

@Path("/berichte/luecken")
@Tag(name = "Berichte")
@Produces(MediaType.APPLICATION_JSON)
public class LueckenberichtResource {

    private static final ZoneId ZONE = ZoneId.of("Europe/Zurich");

    private final VollstaendigkeitAbfrage vollstaendigkeit;
    private final MandantAufloesung aufloesung;
    private final SecurityIdentity identitaet;

    public LueckenberichtResource(VollstaendigkeitAbfrage vollstaendigkeit, MandantAufloesung aufloesung,
                                  SecurityIdentity identitaet) {
        this.vollstaendigkeit = vollstaendigkeit;
        this.aufloesung = aufloesung;
        this.identitaet = identitaet;
    }

    @GET
    @AlleArchivRollen
    @RolesAllowed({"inhaber", "treuhaender", "geschaeftsstelle"})
    @Operation(summary = "Lückenbericht für ein Geschäftsjahr",
            description = "Liefert Lücken nur für Rollen mit uneingeschränkter Sicht auf alle Vertraulichkeitsstufen.")
    @APIResponse(responseCode = "200", description = "Lückenbericht")
    @APIResponse(responseCode = "400", description = "ANFRAGE_UNGUELTIG")
    @APIResponse(responseCode = "403", description = "NICHT_ERLAUBT")
    public VollstaendigkeitAbfrage.Antwort abrufen(
            @Parameter(required = true, description = "Geschäftsjahr (2000 bis zum nächsten Kalenderjahr)")
            @QueryParam("geschaeftsjahr") Integer geschaeftsjahr) {
        if (geschaeftsjahr == null || geschaeftsjahr < VollstaendigkeitAbfrage.JAHR_MIN
                || geschaeftsjahr > LocalDate.now(ZONE).getYear() + 1) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Rolle rolle = Rolle.ausRealmRollen(identitaet.getRoles()).orElseThrow();
        Benutzer benutzer = new Benutzer(identitaet.getPrincipal().getName(), rolle);
        WerkzeugKontext kontext = WerkzeugKontext.fuer(aufloesung.pruefen(), benutzer, null);
        return vollstaendigkeit.vollstaendigkeit(kontext, geschaeftsjahr);
    }
}
