package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import ch.urinet.archiv.ki.domain.chat.ChatBewertung;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;

/** Speichern und Abrufen der eigenen Bewertungen (Spec 02, Requirement 15.2–15.4). */
@ApplicationScoped
public class ChatBewertungen {

    static final int MAX_JE_STUNDE = 200;
    private static final Duration FENSTER = Duration.ofHours(1);

    private final ChatSpeicher speicher;

    public ChatBewertungen(ChatSpeicher speicher) {
        this.speicher = speicher;
    }

    @Transactional
    public ChatBewertung speichern(long nachrichtId, String benutzer, int sterne, ChatBewertung.Grund grund,
                                  String freitext) {
        if (!speicher.bewertbareAntwortSperren(nachrichtId, benutzer)) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        Instant jetzt = Instant.now();
        ChatBewertung bewertung;
        try {
            bewertung = new ChatBewertung(nachrichtId, sterne, grund, freitext, jetzt, jetzt);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        speicher.bewertungVersuchRegistrieren(benutzer, jetzt, MAX_JE_STUNDE, jetzt.minus(FENSTER))
                .ifPresent(wartezeit -> {
                    throw new ZuVieleAnfragen(ZuVieleAnfragen.Grund.CHAT_BEWERTUNGEN, wartezeit);
                });
        Instant erstellt = speicher.bewertung(nachrichtId, benutzer).map(ChatBewertung::erstellt).orElse(jetzt);
        bewertung = new ChatBewertung(nachrichtId, sterne, grund, bewertung.freitext(), erstellt, jetzt);
        speicher.bewertungSpeichern(bewertung, benutzer);
        return bewertung;
    }

    @Transactional
    public ChatBewertung lesen(long nachrichtId, String benutzer) {
        return speicher.bewertung(nachrichtId, benutzer).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
    }

    @Transactional
    public void loeschen(long nachrichtId, String benutzer) {
        if (!speicher.bewertbareAntwortSperren(nachrichtId, benutzer)
                || !speicher.bewertungLoeschen(nachrichtId, benutzer)) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
    }
}
