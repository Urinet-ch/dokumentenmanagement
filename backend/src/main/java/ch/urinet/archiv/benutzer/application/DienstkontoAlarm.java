package ch.urinet.archiv.benutzer.application;

/** Merkt eine endgültige Ablehnung des Keycloak-Dienstkontos dauerhaft zur Benachrichtigung vor. */
public interface DienstkontoAlarm {
    void vormerken(int status);
}
