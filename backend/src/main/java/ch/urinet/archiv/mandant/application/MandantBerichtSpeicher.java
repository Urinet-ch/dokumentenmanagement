package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.MandantBericht;

import java.time.YearMonth;
import java.util.Optional;

/** Driven Port für den einmalig gespeicherten Bericht und den wiederholbaren Mailversand. */
public interface MandantBerichtSpeicher {

    Optional<Eintrag> lesen(YearMonth monat);

    Eintrag speichernFallsFehlt(MandantBericht bericht);

    void mailVersandt(YearMonth monat);

    record Eintrag(MandantBericht bericht, boolean mailVersandt) {
    }
}
