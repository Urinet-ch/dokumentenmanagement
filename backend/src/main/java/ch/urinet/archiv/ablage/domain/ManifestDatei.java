package ch.urinet.archiv.ablage.domain;

import java.util.Objects;

/** Inhalt und Ablage eines gelesenen Manifest-Teils (neueste Version). */
public record ManifestDatei(byte[] inhalt, AbgelegteDatei datei) {

    public ManifestDatei {
        Objects.requireNonNull(inhalt, "inhalt");
        Objects.requireNonNull(datei, "datei");
        inhalt = inhalt.clone();
    }

    @Override
    public byte[] inhalt() {
        return inhalt.clone();
    }
}
