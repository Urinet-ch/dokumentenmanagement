package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.Befund;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

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

    /**
     * Beansprucht einen offenen, noch nicht gemeldeten Alarm; leer, wenn bereits ein Anspruch besteht. Für
     * Verwaltungsalarme läuft ein Anspruch nach zehn Minuten ab und die Zustellung muss bestätigt werden.
     */
    Optional<Instant> beanspruchen(MandantCode mandant, Befund befund);

    /** Bestätigt bei dauerhaft vorgemerkten Verwaltungsalarmen die Zustellung für den aktuellen Anspruch. */
    void bestaetigen(MandantCode mandant, Befund befund, Instant anspruch);

    /**
     * Nimmt die Markierung zurück, weil die Mail nicht zugestellt wurde; der nächste Lauf versucht es wieder. Wirkt nur,
     * solange der Alarm noch den Anspruch {@code anspruch} trägt: Eine verspätete Freigabe hebt keinen neueren,
     * zugestellten Anspruch auf.
     */
    void freigeben(MandantCode mandant, Befund befund, Instant anspruch);
}
