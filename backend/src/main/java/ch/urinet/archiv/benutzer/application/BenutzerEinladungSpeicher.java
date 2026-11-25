package ch.urinet.archiv.benutzer.application;

import java.time.Duration;
import java.time.Instant;

public interface BenutzerEinladungSpeicher {
    /**
     * Speichert den Ablauf des zuletzt versendeten Links. {@code versendetAm} wird unmittelbar vor dem Versand
     * gemessen, damit die gespeicherte Grenze nie nach der echten Grenze des Identitätsdienstes liegt.
     */
    void speichern(String benutzer, Instant versendetAm, Duration gueltigkeit);
}
