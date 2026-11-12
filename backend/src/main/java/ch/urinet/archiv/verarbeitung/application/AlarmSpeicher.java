package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.Befund;

import java.util.Collection;

/**
 * Driven Port: Tabelle {@code alarm} im Schema des Mandanten (design.md «Überwachung»). Je Befund genau eine Mail:
 * Ein Alarm gilt als gemeldet, sobald eine Mail zugestellt ist, und bleibt es, bis der Befund verschwindet.
 */
public interface AlarmSpeicher {

    /**
     * Gleicht die Alarme der Quelle mit den aktuellen Befunden ab: neue anlegen, erledigte wieder öffnen (noch nicht
     * gemeldet) und offene, die nicht mehr vorliegen, als erledigt markieren.
     */
    void abgleichen(MandantCode mandant, Befund.Quelle quelle, Collection<Befund> befunde);

    /** Markiert einen offenen, noch nicht gemeldeten Alarm als gemeldet; {@code false}, wenn er das schon ist. */
    boolean beanspruchen(MandantCode mandant, Befund befund);

    /** Nimmt die Markierung zurück, weil die Mail nicht zugestellt wurde; der nächste Lauf versucht es wieder. */
    void freigeben(MandantCode mandant, Befund befund);
}
