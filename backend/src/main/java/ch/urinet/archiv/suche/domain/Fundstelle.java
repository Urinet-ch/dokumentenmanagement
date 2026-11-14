package ch.urinet.archiv.suche.domain;

/**
 * Bester Abschnitt eines Dokuments aus dem Vektor-Teil der Hybridsuche (Spec 02, Req 2.2; Spec 03, Req 5.7): Nummer des
 * Abschnitts ({@code chunk.nr}) und Seitenbereich; ohne Seitentrenner sind beide Seiten {@code null}.
 */
public record Fundstelle(int abschnitt, Integer seiteVon, Integer seiteBis) {
}
