package ch.urinet.archiv.dokumentart.domain;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

/**
 * Aufbewahrungsregel einer Dokumentart: {@code jahre} ab {@code ab}; beide {@code null} heisst unbefristet (wie der
 * Check {@code dokumentart_unbefristet_ck}).
 */
public record Aufbewahrungsregel(Integer jahre, AufbewahrungAb ab) {

    public static final int JAHRE_MAX = 100;
    public static final Aufbewahrungsregel UNBEFRISTET = new Aufbewahrungsregel(null, null);

    public Aufbewahrungsregel {
        if ((jahre == null) != (ab == null) || jahre != null && (jahre < 1 || jahre > JAHRE_MAX)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    public boolean unbefristet() {
        return jahre == null;
    }
}
