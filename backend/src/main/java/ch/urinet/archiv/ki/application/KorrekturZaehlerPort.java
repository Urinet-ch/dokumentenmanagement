package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.Set;

/**
 * Driven Port für die Kennzahl Korrekturquote je Feld (Req 7.7, 16.4): je bestätigtem Feld ein Zähler «bestätigt», je
 * korrigiertem Feld ein Zähler «korrigiert»; die Quote über ein Zeitfenster rechnet das Monitoring.
 */
public interface KorrekturZaehlerPort {

    void zaehlen(MandantCode mandant, Set<Feld> bestaetigt, Set<Feld> korrigiert);
}
