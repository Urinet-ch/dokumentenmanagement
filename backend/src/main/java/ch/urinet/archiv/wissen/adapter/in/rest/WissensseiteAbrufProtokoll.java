package ch.urinet.archiv.wissen.adapter.in.rest;

import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

/**
 * Schreibt {@code WISSEN_ANZEIGEN} für eine Seite, die ohne {@code {id}} im Pfad abgerufen wurde ({@code ?pfad=}).
 * Prüft die Sicht nicht selbst: nur nach erfolgreichem Sichtentscheid aufrufen (z. B. nach {@code WissenLesen.seite}).
 */
@ApplicationScoped
public class WissensseiteAbrufProtokoll {

    @Protokolliert(aktion = ProtokollAktion.WISSEN_ANZEIGEN, idArgument = 0)
    public void protokollieren(UUID seiteId) {
    }
}
