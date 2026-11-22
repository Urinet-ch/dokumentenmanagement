package ch.urinet.archiv.benutzer.application;

import java.util.UUID;

public interface VerwaltungGrenzen {

    enum Art { BENUTZER, ANLEGEN, EMAIL, EMAIL_AENDERUNG, EINSTELLUNG }

    /**
     * Prüft die Grenzen und sperrt die Prüfung für den Mandanten bis zum Transaktionsende.
     * Der Protokolleintrag für die angenommene Aktion muss in derselben Transaktion geschrieben werden.
     */
    void pruefen(String akteur, Art art, UUID ziel);
}
