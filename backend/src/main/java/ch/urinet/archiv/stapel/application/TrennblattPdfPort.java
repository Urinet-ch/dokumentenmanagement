package ch.urinet.archiv.stapel.application;

import ch.urinet.archiv.stapel.domain.TrennblattSatz;

import java.util.List;

/** Erzeugt das druckfertige PDF der Trennblätter (Spec 09, Req 6.2, 6.3; Adapter mit ZXing und PDFBox). */
public interface TrennblattPdfPort {

    /** A4, je Blatt zwei gleiche Seiten (Vorder- und Rückseite), in der Reihenfolge der Sätze. */
    byte[] erzeugen(List<TrennblattSatz> saetze);
}
