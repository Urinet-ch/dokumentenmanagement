package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.Befund;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Driven Port: Dokumente eines Mandanten für Überwachung und Kennzahlen (Req 16.4, 16.5); nur IDs und Zähler. */
public interface DokumentUeberwachung {

    /** Status, die nach Req 16.5 nicht länger als die Grenze dauern dürfen. */
    List<String> UEBERWACHTE_STATUS = List.of("VERARBEITUNG", "FEHLER", "ARCHIVIERUNG_FEHLER");

    /** Dokumente, die seit mindestens {@code grenze} in einem der {@link #UEBERWACHTE_STATUS} stehen. */
    List<Befund> seitMindestens(MandantCode mandant, Duration grenze);

    /** Anzahl Dokumente je Status ausser den Endzuständen {@code ARCHIVIERT} und {@code VERWORFEN}. */
    Map<String, Long> anzahlJeStatus(MandantCode mandant);
}
