package ch.urinet.archiv.integration.application.mail;

import ch.urinet.archiv.integration.domain.mail.Postfach;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Driven Port zum Postfach des Mail-Eingangs (Spec 02, Req 1.1, 1.4, 1.5; design.md «E-Mail-Eingang»): IMAP über TLS.
 * Ein Abruf liest höchstens {@code max} Nachrichten aus {@code INBOX}, übergibt jede dem {@link Verarbeiter} und
 * verschiebt sie danach in den Ordner, den er nennt. Scheitert der Verarbeiter, bleibt die Nachricht in {@code INBOX}
 * und der nächste Abruf versucht es wieder. Die Dateien einer Nachricht gelten nur während des Aufrufs.
 */
public interface PostfachPort {

    /** @throws PostfachNichtErreichbar wenn Verbindung, Anmeldung oder Ordner scheitern */
    Abruf abrufen(Postfach postfach, String passwort, int max, Verarbeiter verarbeiter);

    @FunctionalInterface
    interface Verarbeiter {
        Ziel verarbeiten(Nachricht nachricht) throws Exception;
    }

    /** IMAP-Ordner nach der Verarbeitung (Req 1.4, 1.5). */
    enum Ziel {
        ARCHIVIERT, QUARANTAENE
    }

    /**
     * Eine Nachricht aus {@code INBOX}.
     *
     * @param messageId Message-ID; fehlt sie im Kopf, {@code sha256:<Prüfsumme der Nachricht>}
     * @param absender  Adresse aus {@code From}, nur wenn es genau eine gibt
     * @param eml       die Nachricht unverändert (RFC 5322)
     * @param anhaenge  alle Teile, die kein Text der Nachricht sind, decodiert, auch aus angehängten Nachrichten
     * @param text      Text der Nachricht ({@code text/plain}, sonst {@code text/html} ohne Markup), leer ohne Text
     */
    record Nachricht(String messageId, Optional<String> absender, Optional<String> betreff, Optional<Instant> datum,
                     Path eml, List<Anhang> anhaenge, String text) {
        public Nachricht {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(absender, "absender");
            Objects.requireNonNull(betreff, "betreff");
            Objects.requireNonNull(datum, "datum");
            Objects.requireNonNull(eml, "eml");
            anhaenge = List.copyOf(anhaenge);
            Objects.requireNonNull(text, "text");
        }
    }

    /** Ein Anhang als Datei mit dem Namen aus der Nachricht (nur zur Anzeige, Spec 01, Req 3.9). */
    record Anhang(Path datei, Optional<String> name) {
        public Anhang {
            Objects.requireNonNull(datei, "datei");
            Objects.requireNonNull(name, "name");
        }
    }

    /** Anzahl verarbeiteter und gescheiterter Nachrichten eines Abrufs. */
    record Abruf(int verarbeitet, int gescheitert) {
    }
}
