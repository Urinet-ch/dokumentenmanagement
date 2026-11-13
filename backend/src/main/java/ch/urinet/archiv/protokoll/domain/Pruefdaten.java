package ch.urinet.archiv.protokoll.domain;

import java.util.Objects;

/** Status des TSA-Zertifikats zum Zeitpunkt des Stempels: OCSP-Antwort oder Sperrliste der CA, je DER (Req 15.11). */
public record Pruefdaten(Art art, byte[] inhalt) {

    public enum Art {
        OCSP,
        CRL
    }

    public Pruefdaten {
        Objects.requireNonNull(art, "art");
        Objects.requireNonNull(inhalt, "inhalt");
    }
}
