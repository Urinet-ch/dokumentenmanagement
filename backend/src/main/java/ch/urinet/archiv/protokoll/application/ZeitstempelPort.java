package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.protokoll.domain.Pruefdaten;
import ch.urinet.archiv.protokoll.domain.Stempel;

import java.time.Instant;
import java.util.List;

/**
 * Driven Port: RFC-3161-Zeitstempeldienste aus {@code archiv.tsa.dienste} (Req 15.3, 15.11; 4.9-E1). An einen Dienst
 * geht nur der Hash (Req 16.7). Endpunkte, Policy-OIDs und ausstellende CAs stehen nur in der Konfiguration.
 */
public interface ZeitstempelPort {

    /** Konfigurierte Dienste in ihrer Reihenfolge; der erste ist der Hauptdienst. */
    List<String> dienste();

    /**
     * Holt einen Stempel über {@code imprint} (SHA-256) mit Nonce und {@code certReq} und prüft die Antwort: Status,
     * Nonce, Imprint, Policy-OID und ausstellende CA aus der Allowlist des Dienstes, Signatur des Tokens.
     *
     * @throws ZeitstempelFehler wenn der Dienst nicht antwortet oder die Antwort nicht besteht
     */
    Stempel stempeln(String dienst, byte[] imprint);

    /** Prüft ein schon abgelegtes {@code .tsr} ohne Netz (Imprint, Policy, CA, Signatur), z. B. bei einer Wiederholung. */
    Stempel pruefen(String dienst, byte[] tsr, byte[] imprint);

    /** Holt OCSP-Antwort bzw. Sperrliste zum TSA-Zertifikat des Stempels und prüft sie (gültig, nicht gesperrt). */
    Pruefdaten pruefdaten(String dienst, Stempel stempel);

    /**
     * Prüfwerkzeug (Req 15.12): Token gegen Imprint, abgelegte Zertifikatskette und abgelegte Prüfdaten, ohne Netz.
     *
     * @return {@code genTime} des Tokens
     * @throws ZeitstempelFehler mit einer Meldung ohne Inhalte, wenn etwas nicht besteht
     */
    Instant pruefenAbgelegt(String dienst, byte[] tsr, byte[] imprint, String kettePem, Pruefdaten pruefdaten);

    /** Ein Dienst antwortet nicht oder seine Antwort besteht die Prüfung nicht. */
    final class ZeitstempelFehler extends RuntimeException {
        public ZeitstempelFehler(String meldung) {
            super(meldung);
        }

        public ZeitstempelFehler(String meldung, Throwable ursache) {
            super(meldung, ursache);
        }
    }
}
