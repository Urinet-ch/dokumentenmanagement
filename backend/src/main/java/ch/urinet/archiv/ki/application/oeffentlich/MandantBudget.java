package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.mandant.domain.MandantCode;

/**
 * Driven Port: Token-Budget des öffentlichen Chatbots je Mandant und Kalendermonat ({@code public.mandant_budget}, Spec 03,
 * Req 6.4; design.md «Data Models»; der Monat gilt in {@code Europe/Zurich}). Ohne Zeile für den Monat gilt der Standard
 * {@code archiv.webseite.chat.monatsbudget-tokens}; eine Zeile des Betreibers setzt {@code tokens_max} je Mandant. Die
 * Wissensbasis (Spec 04) führt ein eigenes Budget und belastet dieses nicht.
 */
public interface MandantBudget {

    /** Ist das Budget des laufenden Monats aufgebraucht (verbraucht ≥ Höchstwert)? */
    boolean erschoepft(MandantCode mandant);

    /** Rechnet {@code tokens} (≥ 0) zum Verbrauch des laufenden Monats; legt die Zeile mit dem Standard-Höchstwert an. Atomar. */
    void verbrauchen(MandantCode mandant, long tokens);
}
