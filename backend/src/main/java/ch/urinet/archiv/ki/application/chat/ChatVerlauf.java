package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.domain.chat.Gespraech;
import ch.urinet.archiv.ki.domain.chat.GespraechKopf;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

/**
 * Gesprächsliste und Verlauf (design.md «Vertrag Welle 7», Verlauf): jeder sieht nur seine eigenen Gespräche, auch der
 * Inhaber keine fremden (Entscheid Fabian 14.11.2026, E3). Ein fremdes oder unbekanntes Gespräch ergibt 404.
 */
@ApplicationScoped
public class ChatVerlauf {

    public static final int LISTE = 20;

    private final ChatSpeicher speicher;

    public ChatVerlauf(ChatSpeicher speicher) {
        this.speicher = speicher;
    }

    public List<GespraechKopf> gespraeche(Benutzer benutzer) {
        return speicher.gespraeche(benutzer.id(), LISTE);
    }

    public Gespraech gespraech(Benutzer benutzer, UUID id) {
        return speicher.gespraech(id, benutzer.id()).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
    }
}
