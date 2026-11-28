package ch.urinet.archiv.stapel.domain;

import java.util.Objects;

/**
 * Ein Satz gleicher Blätter für den Druck (Spec 09, Req 6.1, 6.2): Code, Aufschrift in grosser Schrift und Anzahl.
 *
 * @param aufschrift Name der Art beim Trennblatt mit Art, Titel beim Deckblatt, {@code null} beim Trennblatt ohne Art
 * @param anzahl     Zahl der Blätter, mindestens 1; jedes Blatt hat zwei gleiche Seiten
 */
public record TrennblattSatz(TrennblattCode code, String aufschrift, int anzahl) {

    public TrennblattSatz {
        Objects.requireNonNull(code, "code");
        if (anzahl < 1) {
            throw new IllegalArgumentException("anzahl");
        }
    }
}
