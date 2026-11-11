package ch.urinet.archiv.dokumentart.domain;

import java.util.Arrays;
import java.util.Optional;

/** Beginn der Aufbewahrungsfrist (design.md «Dokumentart und Aufbewahrung»). */
public enum AufbewahrungAb {
    /** Ende des bestätigten Geschäftsjahres */
    GESCHAEFTSJAHR,
    /** {@code gueltig_bis} des Vertrags; ohne {@code gueltig_bis} unbefristet */
    GUELTIG_BIS;

    public static Optional<AufbewahrungAb> aus(String text) {
        return Arrays.stream(values()).filter(v -> v.name().equals(text)).findFirst();
    }
}
