package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

/**
 * Driven Port: Mandant der aktuellen Anmeldung, ausschliesslich aus dem verifizierten Token bzw. API-Token
 * (tech.md Regel 1); nie aus Header, Query-Parameter oder Inhalt der Anfrage.
 */
public interface MandantAusAnmeldung {

    Ergebnis ermitteln();

    sealed interface Ergebnis {
    }

    /** Keine Anmeldung (anonym oder ausserhalb einer Anfrage): nur {@code public}, keine Mandanten-Daten. */
    record Anonym() implements Ergebnis {
    }

    /** Angemeldet, aber Claim fehlt, ist leer, mehrwertig oder kein gültiger Code. */
    record Ungueltig() implements Ergebnis {
    }

    record Gueltig(MandantCode code) implements Ergebnis {
    }
}
