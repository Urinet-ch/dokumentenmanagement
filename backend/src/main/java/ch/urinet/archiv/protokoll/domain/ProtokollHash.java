package ch.urinet.archiv.protokoll.domain;

import java.util.Objects;

/**
 * Hash der Protokollzeilen einer Tabelle im Fenster (Req 15.1; design.md «Manifest», Wahl «SHA-256 je Zeile»): Jede
 * Zeile ergibt ein Blatt {@code SHA-256(JCS(zeile))}, die Blätter stehen in {@code protokoll_blatt}; {@code sha256} ist
 * die SHA-256 der nach {@code id} aneinandergehängten 32-Byte-Blätter (ohne Zeile die SHA-256 der leeren Folge). Wird
 * eine abgelaufene Zeile später gelöscht (Req 9.9, 10.9), bleibt ihr Blatt und damit der Nachweis der übrigen Zeilen
 * erhalten.
 */
public record ProtokollHash(long anzahl, String sha256) {

    public static final String ZUGRIFF_PROTOKOLL = "zugriff_protokoll";
    public static final String DOKUMENT_VERSION = "dokument_version";

    public ProtokollHash {
        if (anzahl < 0) {
            throw new IllegalArgumentException("anzahl ist negativ");
        }
        Objects.requireNonNull(sha256, "sha256");
        Sha256.gueltig(sha256, "sha256");
    }

    /** Wurzel aus den aneinandergehängten Blättern (je 32 Byte). */
    public static ProtokollHash aus(byte[] blaetter) {
        if (blaetter.length % 32 != 0) {
            throw new IllegalArgumentException("Blätter sind nicht je 32 Byte lang");
        }
        return new ProtokollHash(blaetter.length / 32, Sha256.hexVon(blaetter));
    }
}
