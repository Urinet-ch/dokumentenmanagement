package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.application.WissenschatZugang;
import ch.urinet.archiv.ki.domain.chat.Chatmodus;
import ch.urinet.archiv.ki.domain.chat.Gespraech;
import ch.urinet.archiv.ki.domain.chat.GespraechKopf;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

/**
 * Gesprächsliste und Verlauf (design.md «Vertrag Welle 7», Verlauf): jeder sieht nur seine eigenen Gespräche, auch der
 * Inhaber keine fremden (Entscheid Fabian 14.11.2026, E3). Ein fremdes oder unbekanntes Gespräch ergibt 404.
 * <p>
 * Ohne Freischaltung der Wissensbasis (Spec 04, Req 22.6, 22.7) fehlen Gespräche im Modus {@code WISSEN} in der Liste,
 * ihr Verlauf ergibt 403 {@code WISSEN_NICHT_FREIGESCHALTET}; Gespräche im Modus {@code ARCHIV} bleiben lesbar.
 */
@ApplicationScoped
public class ChatVerlauf {

    public static final int LISTE = 20;

    private final ChatSpeicher speicher;
    private final WissenschatZugang wissenschat;

    public ChatVerlauf(ChatSpeicher speicher, WissenschatZugang wissenschat) {
        this.speicher = speicher;
        this.wissenschat = wissenschat;
    }

    public List<GespraechKopf> gespraeche(Benutzer benutzer) {
        List<GespraechKopf> liste = speicher.gespraeche(benutzer.id(), LISTE);
        return wissenVerfuegbar() ? liste
                : liste.stream().filter(kopf -> kopf.modus() != Chatmodus.WISSEN).toList();
    }

    public Gespraech gespraech(Benutzer benutzer, UUID id) {
        Gespraech gespraech = speicher.gespraech(id, benutzer.id())
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (gespraech.kopf().modus() == Chatmodus.WISSEN) {
            wissenschat.freischaltungPruefen();
        }
        return gespraech;
    }

    /**
     * Wissen für den Mandanten der Anfrage freigeschaltet? Ohne Freischaltung zeigt der Verlauf Quellen auf
     * Wissensseiten ohne Titel und Link und bietet keine Analyse an (Spec 04, Req 22.5, 22.7).
     */
    public boolean wissenVerfuegbar() {
        return wissenschat.freigeschaltet();
    }
}
