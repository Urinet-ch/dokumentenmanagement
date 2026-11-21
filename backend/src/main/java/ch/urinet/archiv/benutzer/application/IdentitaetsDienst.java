package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.sicherheit.domain.Rolle;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Identitätsquelle für die Benutzerverwaltung. Der Mandant stammt beim Aufrufer aus dem verifizierten Token;
 * jede Operation mit einer Benutzerkennung prüft die Zugehörigkeit erneut.
 */
public interface IdentitaetsDienst {

    /**
     * Der Identitätsdienst meldet einen fachlichen Konflikt (HTTP 409, z. B. Benutzername oder E-Mail vergeben).
     * Der Konflikt ist kein Ausfall des Dienstes und zählt nicht für den Schutzschalter. Die Abbildung auf den
     * REST-Fehler {@code BENUTZER_EXISTIERT} folgt mit Aufgabe 19.7; die Meldung nennt weder Feld noch Mandant.
     */
    final class KonfliktException extends RuntimeException {
        public KonfliktException() {
            super("Konflikt im Identitätsdienst", null, false, false);
        }
    }

    enum ErforderlicheAktion {
        VERIFY_EMAIL, UPDATE_PASSWORD, CONFIGURE_TOTP
    }

    record Benutzer(String id, String benutzername, String email, String vorname, String nachname,
                    boolean aktiv, Set<Rolle> rollen, Set<ErforderlicheAktion> aktionen,
                    boolean totp, boolean google) {
        public Benutzer {
            rollen = Set.copyOf(rollen);
            aktionen = Set.copyOf(aktionen);
        }

        @Override
        public String toString() {
            return "Benutzer[geschützt]";
        }
    }

    record Neuanlage(String benutzername, String email, String vorname, String nachname) {
        @Override
        public String toString() {
            return "Neuanlage[geschützt]";
        }
    }

    /** Nur diese Felder dürfen geändert werden; das Mandantenattribut ist unveränderlich. */
    record Aenderung(String email, String vorname, String nachname) {
        @Override
        public String toString() {
            return "Aenderung[geschützt]";
        }
    }

    List<Benutzer> benutzerSuchen(String mandant, String suche, int seite);

    Benutzer benutzerLesen(String mandant, String id);

    String anlegen(String mandant, Neuanlage benutzer, Rolle rolle);

    void aktualisieren(String mandant, String id, Aenderung aenderung);

    void rolleSetzen(String mandant, String id, Rolle rolle);

    void aktivieren(String mandant, String id, boolean aktiv);

    void einladen(String mandant, String id, Set<ErforderlicheAktion> aktionen, Duration gueltigkeit);

    void sitzungenBeenden(String mandant, String id);

    void otpEntfernen(String mandant, String id);

    void sperreAufheben(String mandant, String id);

    void googleLoesen(String mandant, String id);

    void loeschen(String mandant, String id);

    long inhaberZaehlen(String mandant);

    /** Prüft die Verbindung des Dienstkontos; Fehler werden nicht als Erfolg dargestellt. */
    void verbindungPruefen();
}
