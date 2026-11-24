package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

@ApplicationScoped
public class Webseitenfreigabe {
    private final WissenHuelleDienst huelle;
    private final WebseitenfreigabePort port;
    private final WissenSichtbarkeit sichtbarkeit;
    private final Berechtigung berechtigung;

    public Webseitenfreigabe(WissenHuelleDienst huelle, WebseitenfreigabePort port,
                             WissenSichtbarkeit sichtbarkeit, Berechtigung berechtigung) {
        this.huelle = huelle;
        this.port = port;
        this.sichtbarkeit = sichtbarkeit;
        this.berechtigung = berechtigung;
    }

    @Transactional
    public WebseitenfreigabePort.Stand lesen(Benutzer benutzer, UUID seiteId) {
        var stand = port.lesen(seiteId, false);
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15,
                stand.isPresent() && sichtbarkeit.aktuelleSeiteSichtbar(benutzer, seiteId));
        return stand.orElseThrow();
    }

    @Transactional
    public void setzen(Benutzer benutzer, UUID seiteId, boolean freigeben,
                       Supplier<QuellenPruefungPort.SeitenStand> ifMatch) {
        huelle.sperren();
        var seite = port.lesen(seiteId, true);
        berechtigung.wissensseitenAktion(benutzer, Aktion.A16,
                seite.isPresent() && sichtbarkeit.aktuelleSeiteSichtbar(benutzer, seiteId));
        var stand = seite.orElseThrow();
        var erwartet = ifMatch.get();
        if (stand.version() != erwartet.version() || stand.quellenRevision() != erwartet.quellenRevision()) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        if (stand.freigegeben() == freigeben || (freigeben && !stand.freigabefaehig())) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        port.setzen(seiteId, stand, freigeben, benutzer.id());
    }
}
