package ch.urinet.archiv.wissen.application;

import java.util.UUID;

/**
 * Schreibt {@code WISSEN_ANZEIGEN} für eine Seite, die nicht über die Seiten-ID im Pfad abgerufen wurde (Abruf per
 * {@code ?pfad=}); der Interceptor kennt nur den Pfadparameter {@code id}.
 */
public interface WissenAnzeigeProtokoll {

    void seiteAngezeigt(UUID seiteId);
}
