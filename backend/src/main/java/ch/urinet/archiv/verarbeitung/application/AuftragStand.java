package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;

import java.util.Optional;

/** Driven Port: Status des jüngsten Auftrags eines Typs je Mandant in {@code public.auftrag} (Req 15.8, 16.5). */
public interface AuftragStand {

    /** {@code OFFEN}, {@code LAEUFT}, {@code FERTIG} oder {@code FEHLER}; leer, wenn es keinen Auftrag gibt. */
    Optional<String> juengsterStatus(MandantCode mandant, AuftragTyp typ);
}
