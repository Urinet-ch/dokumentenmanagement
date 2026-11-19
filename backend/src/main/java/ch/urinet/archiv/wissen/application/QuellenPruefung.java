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
public class QuellenPruefung {
    private final WissenHuelleDienst huelle;
    private final QuellenPruefungPort port;
    private final WissenSichtbarkeit sichtbarkeit;
    private final Berechtigung berechtigung;

    public QuellenPruefung(WissenHuelleDienst huelle, QuellenPruefungPort port,
                           WissenSichtbarkeit sichtbarkeit, Berechtigung berechtigung) {
        this.huelle = huelle;
        this.port = port;
        this.sichtbarkeit = sichtbarkeit;
        this.berechtigung = berechtigung;
    }

    @Transactional
    public void bestaetigen(Benutzer benutzer, UUID seiteId, Supplier<QuellenPruefungPort.SeitenStand> ifMatch) {
        huelle.sperren();
        var seite = port.seiteSperren(seiteId);
        berechtigung.wissensseitenAktion(benutzer, Aktion.A17,
                seite.isPresent() && sichtbarkeit.aktuelleSeiteSichtbar(benutzer, seiteId));
        var stand = seite.orElseThrow();
        var erwartet = ifMatch.get();
        if (stand.version() != erwartet.version() || stand.quellenRevision() != erwartet.quellenRevision()) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        if (!stand.quellePruefen()) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        port.bestaetigen(seiteId, stand, benutzer.id());
    }
}
