package ch.urinet.archiv.dokumentart.domain;

import java.util.Arrays;
import java.util.Optional;

/** Standard-Vertraulichkeit einer Dokumentart (product.md «Benutzer und Rollen»); gespeichert als Text. */
public enum Vertraulichkeit {
    NORMAL, INTERN, PERSONAL;

    public static Optional<Vertraulichkeit> aus(String text) {
        return Arrays.stream(values()).filter(v -> v.name().equals(text)).findFirst();
    }
}
