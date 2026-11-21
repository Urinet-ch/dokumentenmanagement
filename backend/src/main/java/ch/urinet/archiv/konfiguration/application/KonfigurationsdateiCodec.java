package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;

public interface KonfigurationsdateiCodec {

    Datei lesen(String text);

    /** Wie {@link #lesen}, für den Seed eines Mandanten mit der Seed-Frist ({@code archiv.dokumentart.seed-zeitgrenze}). */
    default Datei seedLesen(String text) {
        return lesen(text);
    }

    String schreiben(Konfiguration inhalt);

    record Datei(Integer basisVersion, Konfiguration inhalt) {
    }
}
