package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;

/**
 * Driven Port: Zugang zum Wissenschat (Spec 04, Requirement 21.1 und 21.7). Prüft nur, schreibt nichts; der Adapter liegt
 * auf der Seite der Wissensbasis.
 */
public interface WissenschatZugang {

    /** Recht A15; ohne es eine Verweigerung (403, protokolliert). */
    void rechtPruefen(Benutzer benutzer);

    /** Wissen für diesen Mandanten freigeschaltet (Spec 04, Req 22.7)? Fehler ergeben {@code false}. */
    boolean freigeschaltet(MandantCode mandant);

    /** Wie {@link #freigeschaltet(MandantCode)} für den Mandanten der Anfrage bzw. des Kontexts. */
    boolean freigeschaltet();

    /** Nicht freigeschaltet: 403 {@code WISSEN_NICHT_FREIGESCHALTET} (Req 22.6), vor Recht und Budget. */
    default void freischaltungPruefen() {
        if (!freigeschaltet()) {
            throw new ch.urinet.archiv.gemeinsam.domain.FachFehler(
                    ch.urinet.archiv.gemeinsam.domain.Fehlercode.WISSEN_NICHT_FREIGESCHALTET);
        }
    }

    /** Monatsbudget der Wissensbasis; erschöpft 429 {@code BUDGET}. */
    void budgetPruefen();
}
