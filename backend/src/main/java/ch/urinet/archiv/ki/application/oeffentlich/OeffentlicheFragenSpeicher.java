package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.ki.domain.oeffentlich.OeffentlicheQuelle;

import java.time.Instant;
import java.util.List;

/**
 * Driven Port: {@code oeffentliche_frage} im Schema des Mandanten aus dem Kontext (Spec 03, Req 6.5): bereinigte Frage,
 * Quellen und Tokens, nie Antwort, Gespräch, Client-Hash, IP, Schlüssel oder Webseite. Jede Operation in eigener
 * Transaktion.
 */
public interface OeffentlicheFragenSpeicher {

    void speichern(String frage, List<OeffentlicheQuelle> quellen, int tokens, Instant zeit);

    /** Löscht, was vor {@code grenze} liegt; Anzahl der gelöschten Zeilen. */
    int loeschenVor(Instant grenze);
}
