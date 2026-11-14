package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.ki.domain.chat.Aufgeraeumt;
import ch.urinet.archiv.ki.domain.chat.ChatAntwort;
import ch.urinet.archiv.ki.domain.chat.Gespraech;
import ch.urinet.archiv.ki.domain.chat.GespraechKopf;
import ch.urinet.archiv.ki.domain.chat.VerlaufEintrag;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port: {@code chat_gespraech} und {@code chat_nachricht} des Mandanten aus dem Kontext (Changeset 027).
 * {@code benutzer} ist die Benutzer-ID (Claim {@code sub}) wie im Zugriffsprotokoll.
 */
public interface ChatSpeicher {

    /** Sperrt die Fragen des Benutzers bis zum Ende der laufenden Transaktion (Zählen und Speichern ohne Wettlauf). */
    void benutzerSperren(String benutzer);

    /** Gehört das Gespräch dem Benutzer? Sperrt es dann bis zum Ende der Transaktion gegen den Aufräumer. */
    boolean gehoertSperren(UUID gespraech, String benutzer);

    /**
     * Zeit der {@code grenze}-neuesten Frage des Benutzers nach {@code seit}; leer, wenn es weniger als {@code grenze}
     * Fragen sind.
     */
    Optional<Instant> grenzFrage(String benutzer, Instant seit, int grenze);

    void gespraechAnlegen(UUID id, String benutzer, String titel, Instant zeit);

    /** Die letzten {@code anzahl} Fragen und Antworten mit Inhalt, älteste zuerst. */
    List<VerlaufEintrag> verlauf(UUID gespraech, int anzahl);

    /** Speichert die Frage und setzt {@code geaendert}. */
    void frageSpeichern(UUID gespraech, String frage, Instant zeit);

    /** Speichert die Antwort und setzt {@code geaendert}, in einer eigenen Transaktion. */
    void antwortSpeichern(UUID gespraech, ChatAntwort antwort, Instant zeit);

    /** Die neuesten {@code anzahl} Gespräche des Benutzers nach {@code geaendert}. */
    List<GespraechKopf> gespraeche(String benutzer, int anzahl);

    /** Das Gespräch mit Nachrichten, nur wenn es dem Benutzer gehört. */
    Optional<Gespraech> gespraech(UUID id, String benutzer);

    /** Löscht Nachrichten mit {@code zeit} vor {@code grenze}, danach Gespräche ohne Nachrichten (Requirement 5.4). */
    Aufgeraeumt aufraeumen(Instant grenze);
}
