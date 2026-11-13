package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.Map;

/** Driven Port: Aufträge eines Mandanten je Status für die Kennzahlen (Req 16.4), ohne {@code FERTIG}. */
public interface AuftragZaehler {

    Map<String, Long> anzahlJeStatus(MandantCode mandant);
}
