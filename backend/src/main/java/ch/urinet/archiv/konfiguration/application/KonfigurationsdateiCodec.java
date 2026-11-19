package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;

public interface KonfigurationsdateiCodec {

    Datei lesen(String text);

    String schreiben(Konfiguration inhalt);

    record Datei(Integer basisVersion, Konfiguration inhalt) {
    }
}
