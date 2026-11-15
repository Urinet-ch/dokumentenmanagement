package ch.urinet.archiv.ablage.domain;

/**
 * Unter einem Schlüssel unter {@code manifest/} ist die älteste Version nicht eindeutig (gleicher Zeitpunkt oder ohne
 * Zeitpunkt; design.md «Manifest», «Umsetzung», Nachtrag Review PR #87). Lesen und Ablegen brechen ab, statt eine
 * zufällig zuerst gelistete Version zu nehmen; der Prüflauf wertet das Manifest als unbrauchbar (Req 15.5).
 */
public class MehrdeutigeVersion extends IllegalStateException {

    public MehrdeutigeVersion(String meldung) {
        super(meldung);
    }
}
