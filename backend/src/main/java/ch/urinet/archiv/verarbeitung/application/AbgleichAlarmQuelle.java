package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.Befund;

import java.util.List;

/** Noch aktive Abgleichbefunde; ihr Lebenszyklus wird beim täglichen Abgleich bestimmt. */
public interface AbgleichAlarmQuelle {
    List<Befund> offene(MandantCode mandant);
}
