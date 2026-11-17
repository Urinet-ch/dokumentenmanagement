package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.MandantBericht;

/** Driven Port für die Betreiber-Mail; {@code false} lässt den gespeicherten Bericht erneut zustellbar. */
public interface MandantBerichtMail {

    boolean senden(MandantBericht bericht);
}
