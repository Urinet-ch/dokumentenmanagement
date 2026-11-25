package ch.urinet.archiv.benutzer.application;

import java.time.Duration;

public interface BenutzerEinladungSpeicher {
    void speichern(String benutzer, Duration gueltigkeit);
}
