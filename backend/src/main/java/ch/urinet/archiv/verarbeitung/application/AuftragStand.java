package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;

import java.time.Instant;
import java.util.Optional;

/** Driven Port: Stand der Aufträge eines Typs je Mandant in {@code public.auftrag} (Req 15.8, 16.5). */
public interface AuftragStand {

    /**
     * Ende ({@code beendet}) des zuletzt in {@code FEHLER} beendeten Auftrags, unabhängig von neueren Aufträgen in
     * {@code OFFEN}, {@code LAEUFT} oder {@code FERTIG}; leer, wenn kein Auftrag gescheitert ist.
     */
    Optional<Instant> letzterFehler(MandantCode mandant, AuftragTyp typ);
}
