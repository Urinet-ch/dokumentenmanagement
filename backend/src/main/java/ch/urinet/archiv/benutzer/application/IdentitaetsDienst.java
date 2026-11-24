package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.sicherheit.domain.Rolle;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Identitätsquelle für die Benutzerverwaltung. Der Mandant stammt beim Aufrufer aus dem verifizierten Token;
 * jede Operation mit einer Benutzerkennung prüft die Zugehörigkeit erneut.
 */
public interface IdentitaetsDienst {

    int MAX_SEITENGROESSE = 50;

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

    /**
     * Fehler nach erfolgreicher Anlage; das Ziel wird für den Abschluss des Verwaltungsvorgangs benötigt.
     * Eine bestätigte Entfernung allein beweist nicht, dass keine verspätete externe Wirkung mehr eintritt.
     */
    final class AnlageException extends RuntimeException {
        private final String ziel;
        private final boolean entfernt;

        public AnlageException(String ziel, boolean entfernt, RuntimeException ursache) {
            super("Benutzeranlage nach Erstellung fehlgeschlagen", ursache);
            this.ziel = Objects.requireNonNull(ziel);
            this.entfernt = entfernt;
        }

        public String ziel() {
            return ziel;
        }

        public boolean entfernt() {
            return entfernt;
        }
    }

    enum ErforderlicheAktion {
        VERIFY_EMAIL, UPDATE_PASSWORD, CONFIGURE_TOTP
    }

    record Benutzer(String id, String benutzername, String email, String vorname, String nachname,
                    boolean aktiv, Set<Rolle> rollen, Set<ErforderlicheAktion> aktionen,
                    boolean totp, boolean google, int passkeys) {
        public Benutzer {
            rollen = Set.copyOf(rollen);
            aktionen = Set.copyOf(aktionen);
            if (passkeys < 0) {
                throw new IllegalArgumentException("Ungültige Zahl der Passkeys");
            }
        }

        /** Ohne Passkey; die Zahl der Passkeys liefert nur der Identitätsdienst (Requirement 10.6). */
        public Benutzer(String id, String benutzername, String email, String vorname, String nachname,
                        boolean aktiv, Set<Rolle> rollen, Set<ErforderlicheAktion> aktionen,
                        boolean totp, boolean google) {
            this(id, benutzername, email, vorname, nachname, aktiv, rollen, aktionen, totp, google, 0);
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

    default List<Benutzer> benutzerSuchen(String mandant, String suche, int seite) {
        return benutzerSuchen(mandant, suche, seite, MAX_SEITENGROESSE);
    }

    List<Benutzer> benutzerSuchen(String mandant, String suche, int seite, int groesse);

    /**
     * Wie {@link #benutzerSuchen(String, String, int, int)}, zusätzlich gefiltert nach «Passkey eingerichtet»
     * ({@code true}: mindestens ein Passkey, {@code false}: keiner, {@code null}: alle; Requirement 10.6). Die Seite
     * zählt nach dem Filter.
     */
    default List<Benutzer> benutzerSuchen(String mandant, String suche, int seite, int groesse, Boolean passkey) {
        if (passkey == null) {
            return benutzerSuchen(mandant, suche, seite, groesse);
        }
        int start = seitenanfang(suche, seite, groesse);
        List<Benutzer> ergebnis = new java.util.ArrayList<>();
        long passende = 0;
        for (int quellSeite = 0; ; quellSeite++) {
            List<Benutzer> teil = benutzerSuchen(mandant, suche, quellSeite, MAX_SEITENGROESSE);
            for (Benutzer benutzer : teil) {
                if ((benutzer.passkeys() > 0) == passkey && passende++ >= start) {
                    ergebnis.add(benutzer);
                    if (ergebnis.size() == groesse) {
                        return List.copyOf(ergebnis);
                    }
                }
            }
            if (teil.size() < MAX_SEITENGROESSE) {
                return List.copyOf(ergebnis);
            }
        }
    }

    static int seitenanfang(String suche, int seite, int groesse) {
        long start = (long) seite * groesse;
        if (seite < 0 || groesse < 1 || groesse > MAX_SEITENGROESSE || start > Integer.MAX_VALUE
                || (suche != null && suche.length() > 40)) {
            throw new IllegalArgumentException("Ungültige Suchanfrage");
        }
        return (int) start;
    }

    static boolean passtSuche(String suche, String... felder) {
        if (suche == null || suche.isBlank()) {
            return true;
        }
        String filter = suche.toLowerCase(Locale.ROOT);
        return Arrays.stream(felder).filter(Objects::nonNull)
                .anyMatch(wert -> wert.toLowerCase(Locale.ROOT).contains(filter));
    }

    Benutzer benutzerLesen(String mandant, String id);

    String anlegen(String mandant, Neuanlage benutzer, Rolle rolle);

    /** Für einen Vier-Augen-Antrag: Ein Benutzer ohne Archiv-Rolle erhält keinen Zugang zum Archiv. */
    String anlegenOhneRolle(String mandant, Neuanlage benutzer);

    void aktualisieren(String mandant, String id, Aenderung aenderung);

    void rolleSetzen(String mandant, String id, Rolle rolle);

    void aktivieren(String mandant, String id, boolean aktiv);

    void aktionenHinzufuegen(String mandant, String id, Set<ErforderlicheAktion> aktionen);

    void einladen(String mandant, String id, Set<ErforderlicheAktion> aktionen, Duration gueltigkeit);

    void sitzungenBeenden(String mandant, String id);

    void otpEntfernen(String mandant, String id);

    /**
     * Löscht ausschliesslich die Passkeys (Credential-Typ {@code webauthn-passwordless}), nie Passwort oder TOTP
     * (Requirement 10.5). Liefert die Zahl der gelöschten Passkeys; Kennungen und Gerätedaten gibt der Dienst nicht weiter.
     */
    int passkeysEntfernen(String mandant, String id);

    void sperreAufheben(String mandant, String id);

    boolean anmeldungGesperrt(String mandant, String id);

    void googleLoesen(String mandant, String id);

    void loeschen(String mandant, String id);

    /** Entfernt einen gerade angelegten Benutzer auch bei offenem Schutzschalter. */
    void anlageRueckgaengig(String mandant, String id);

    /** Alle nicht gelöschten Benutzer des Mandanten, auch deaktivierte und eingeladene. */
    long benutzerZaehlen(String mandant);

    /** Aktive Inhaber ohne offene erforderliche Aktionen; deaktivierte Inhaber werden nicht mitgezählt. */
    long inhaberZaehlen(String mandant);

    /** Prüft die Verbindung des Dienstkontos; Fehler werden nicht als Erfolg dargestellt. */
    void verbindungPruefen();
}
