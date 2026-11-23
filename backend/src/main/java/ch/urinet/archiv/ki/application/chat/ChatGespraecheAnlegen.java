package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.application.WissenschatZugang;
import ch.urinet.archiv.ki.domain.chat.Chatmodus;
import ch.urinet.archiv.ki.domain.chat.GespraechKopf;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Legt ein Chat-Gespräch mit festem Modus an, bevor die erste Frage gestellt wird (Spec 04, Requirement 21.1 und 21.5,
 * {@code POST /chat/gespraeche}). Der Modus {@code WISSEN} setzt das Recht A15 voraus (sonst 403); ein unbekannter Modus
 * ist 400 {@code ANFRAGE_UNGUELTIG}. Je Benutzer und Modus gibt es höchstens ein Gespräch ohne Nachrichten: Wer erneut
 * anlegt, erhält dieses wieder, sodass wiederholtes Anlegen keine Gespräche anhäuft. Der Titel ist bis zur ersten Frage
 * {@value #TITEL_NEU}; die erste Frage ersetzt ihn ({@link ChatFragen#annehmen}). Der Modus wechselt nie.
 */
@ApplicationScoped
public class ChatGespraecheAnlegen {

    public static final String TITEL_NEU = "Neues Gespräch";

    private final ChatSpeicher speicher;
    private final WissenschatZugang wissenschat;
    private final Clock uhr = Clock.systemUTC();

    public ChatGespraecheAnlegen(ChatSpeicher speicher, WissenschatZugang wissenschat) {
        this.speicher = speicher;
        this.wissenschat = wissenschat;
    }

    /** Der Modus aus dem Anfragekörper; fehlt er (null), gilt {@link Chatmodus#ARCHIV}. */
    public static Chatmodus modus(String roh) {
        if (roh == null) {
            return Chatmodus.ARCHIV;
        }
        return Chatmodus.aus(roh).orElseThrow(() -> new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG));
    }

    /** Das Gespräch und ob es neu entstanden ist (sonst war es das noch nicht gefragte desselben Modus). */
    public record Ergebnis(GespraechKopf kopf, boolean neu) {
    }

    @Transactional
    public Ergebnis anlegen(Benutzer benutzer, Chatmodus modus) {
        if (modus == Chatmodus.WISSEN) {
            wissenschat.rechtPruefen(benutzer);
        }
        speicher.benutzerSperren(benutzer.id());
        Instant jetzt = uhr.instant();
        Optional<UUID> leeres = speicher.leeresGespraechSperren(benutzer.id(), modus);
        UUID id = leeres.orElseGet(UUID::randomUUID);
        if (leeres.isEmpty()) {
            speicher.gespraechAnlegen(id, benutzer.id(), TITEL_NEU, modus, jetzt);
        }
        return new Ergebnis(speicher.gespraech(id, benutzer.id()).orElseThrow().kopf(), leeres.isEmpty());
    }
}
