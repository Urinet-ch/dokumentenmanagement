package ch.urinet.archiv.dokument.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Protokoll eines Konflikts bei der Neuberechnung der Frist (Req 9.7, design.md «Protokoll und Manifest»): Aktion
 * {@code SPERRE_KONFLIKT} mit der bisherigen Frist, die bleibt, und der neu berechneten; {@code null} heisst unbefristet.
 * In der Transaktion des Aufrufers; Benutzer, Rolle und IP kommen aus der auslösenden Anfrage.
 */
public interface SperrKonfliktProtokollPort {

    void sperreKonflikt(UUID dokumentId, LocalDate bisher, LocalDate neu);
}
