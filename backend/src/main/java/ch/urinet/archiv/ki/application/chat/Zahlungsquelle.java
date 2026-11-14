package ch.urinet.archiv.ki.application.chat;

/**
 * Woher der Zahlstatus und das Zahlungsdatum der Rechnungen kommen (Spec 02, Req 3.4; Spec 05, 8.8 minimal). Ohne
 * AbaNinja-Verbindung (Welle 8) gibt es keine Quelle: Die Werkzeuge melden das ausdrücklich, statt zu raten. Welle 8
 * ersetzt die Standardbelegung {@link OhneZahlungsquelle}; die Werkzeuge ändern sich dafür nicht.
 */
public interface Zahlungsquelle {

    String UNBEKANNT = "UNBEKANNT";

    /** Sind Zahlstatus und {@code bezahlt_am} verlässlich gepflegt (AbaNinja verbunden)? */
    boolean verbunden();

    /** Zahlstatus eines Belegs aus der gespeicherten Angabe; ohne Verbindung oder ohne Angabe {@link #UNBEKANNT}. */
    default String zahlstatus(String gespeichert) {
        return verbunden() && gespeichert != null && !gespeichert.isBlank() ? gespeichert : UNBEKANNT;
    }
}
