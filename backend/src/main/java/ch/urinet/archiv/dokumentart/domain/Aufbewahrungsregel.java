package ch.urinet.archiv.dokumentart.domain;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

/**
 * Aufbewahrungsregel einer Dokumentart: {@code jahre} ab {@code ab}; beide {@code null} heisst unbefristet (wie der
 * Check {@code dokumentart_unbefristet_ck}).
 */
public record Aufbewahrungsregel(Integer jahre, AufbewahrungAb ab, String feld) {

    public static final int JAHRE_MAX = 100;
    /** Req 9.3: «plus Jahre der Regel (Standard 10)». */
    public static final int JAHRE_STANDARD = 10;
    public static final Aufbewahrungsregel UNBEFRISTET = new Aufbewahrungsregel(null, null);

    public Aufbewahrungsregel(Integer jahre, AufbewahrungAb ab) {
        this(jahre, ab, null);
    }

    public Aufbewahrungsregel {
        if ((jahre == null) != (ab == null) || jahre != null && (jahre < 1 || jahre > JAHRE_MAX)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if ((ab == AufbewahrungAb.FELD) != (feld != null)
                || feld != null && !feld.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
    }

    public boolean unbefristet() {
        return jahre == null;
    }
}
