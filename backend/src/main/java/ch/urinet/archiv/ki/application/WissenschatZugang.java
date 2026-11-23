package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.sicherheit.domain.Benutzer;

/**
 * Driven Port: Zugang zum Wissenschat (Spec 04, Requirement 21.1 und 21.7). Prüft nur, schreibt nichts; der Adapter liegt
 * auf der Seite der Wissensbasis.
 */
public interface WissenschatZugang {

    /** Recht A15; ohne es eine Verweigerung (403, protokolliert). */
    void rechtPruefen(Benutzer benutzer);

    /** Monatsbudget der Wissensbasis; erschöpft 429 {@code BUDGET}. */
    void budgetPruefen();
}
