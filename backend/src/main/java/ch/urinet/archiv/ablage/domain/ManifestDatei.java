package ch.urinet.archiv.ablage.domain;

import java.util.Objects;

/**
 * Inhalt und Ablage eines gelesenen Manifest-Teils: die älteste (gesperrte) Version. {@code weitere} zählt die übrigen
 * Versionen und Löschmarken unter demselben Schlüssel; jeder Schlüssel unter {@code manifest/} wird nur einmal
 * geschrieben, mehr als eine Version ist darum ein Fehler (Req 6.11, 15.4).
 */
public record ManifestDatei(byte[] inhalt, AbgelegteDatei datei, int weitere) {

    public ManifestDatei {
        Objects.requireNonNull(inhalt, "inhalt");
        Objects.requireNonNull(datei, "datei");
        if (weitere < 0) {
            throw new IllegalArgumentException("weitere < 0");
        }
        inhalt = inhalt.clone();
    }

    public ManifestDatei(byte[] inhalt, AbgelegteDatei datei) {
        this(inhalt, datei, 0);
    }

    @Override
    public byte[] inhalt() {
        return inhalt.clone();
    }
}
