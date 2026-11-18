package ch.urinet.archiv.dokumentart.domain;

import java.time.LocalDate;

/** Datumswert eines benannten Zusatzfeldes; ohne Wert gilt die Frist als unbefristet. */
@FunctionalInterface
public interface Fristbezug {
    LocalDate datum(String feld);
}
