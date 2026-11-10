package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;

/**
 * Geholter Auftrag aus {@code public.auftrag}. {@code versuche} zählt die bisherigen Fehlversuche; zusammen mit der ID
 * bildet er den Anspruch des Workers: Wer den Auftrag freigibt (Aufräumer) oder neu holt, erhöht ihn, danach greift
 * kein Schreiben des alten Workers mehr.
 */
public record Auftrag(long id, MandantCode mandant, AuftragTyp typ, Nutzlast nutzlast, int versuche) {
}
