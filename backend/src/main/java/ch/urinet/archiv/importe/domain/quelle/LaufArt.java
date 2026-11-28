package ch.urinet.archiv.importe.domain.quelle;

/**
 * Art eines Quelllaufs: {@code TROCKEN} legt nichts an (10.5), {@code ECHT} nimmt Uploads auf, {@code AUTOMATISCH}
 * legt der Server je Quelle und Tag an, wenn ein Upload nur den Code der Quelle nennt.
 */
public enum LaufArt {
    TROCKEN, ECHT, AUTOMATISCH
}
