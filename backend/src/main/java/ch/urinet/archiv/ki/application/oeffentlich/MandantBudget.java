package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.time.LocalDate;

/**
 * Driven Port: Token-Budget des öffentlichen Chatbots je Mandant und Kalendermonat ({@code public.mandant_budget}, Spec 03,
 * Req 6.4; design.md «Data Models»; der Monat gilt in {@code Europe/Zurich}). Ohne Zeile für den Monat gilt der Standard
 * {@code archiv.webseite.chat.monatsbudget-tokens}; eine Zeile des Betreibers setzt {@code tokens_max} je Mandant. Eine
 * atomare Reservierung lässt höchstens eine öffentliche Frage je Mandant und Monat gleichzeitig laufen. Die Wissensbasis
 * (Spec 04) führt ein eigenes Budget und belastet dieses nicht.
 */
public interface MandantBudget {

    enum ReservierungsStatus {
        RESERVIERT,
        BUDGET_ERSCHOEPFT,
        BEREITS_LAUFEND
    }

    record Reservierung(ReservierungsStatus status, LocalDate monat) {
    }

    /** Reserviert atomar den einzigen laufenden Platz des aktuellen Monats und gibt den Grund einer Ablehnung zurück. */
    Reservierung reservieren(MandantCode mandant);

    /** Gibt die Reservierung des Monats frei; idempotent. */
    void freigeben(MandantCode mandant, LocalDate monat);

    /** Rechnet {@code tokens} (≥ 0) atomar zum Verbrauch des angegebenen Monats. */
    void verbrauchen(MandantCode mandant, LocalDate monat, long tokens);
}
