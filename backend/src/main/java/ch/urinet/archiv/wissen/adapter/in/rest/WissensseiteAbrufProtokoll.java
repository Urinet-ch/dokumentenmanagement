package ch.urinet.archiv.wissen.adapter.in.rest;

import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class WissensseiteAbrufProtokoll {

    @Protokolliert(aktion = ProtokollAktion.WISSEN_ANZEIGEN, idArgument = 0)
    public void protokollieren(UUID seiteId) {
    }
}
