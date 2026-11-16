package ch.urinet.archiv.integration.domain.webseite;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Zugriffsstufe eines Dokuments und einer Anfrage der Webseiten-Schnittstelle (Spec 03, Req 3.7): {@code OEFFENTLICH}
 * (alle Besucher) &lt; {@code MITGLIEDER} (angemeldete Verbandsmitglieder) &lt; {@code VORSTAND}. Eine höhere Stufe sieht
 * alles, was eine tiefere sieht. Die Ordnung ist die Reihenfolge der Konstanten; neue Stufen gehören an die richtige
 * Stelle, nie ans Ende.
 */
public enum Zugriffsstufe {
    OEFFENTLICH, MITGLIEDER, VORSTAND;

    /** Wert im Dokumentfeld {@code zusatz.zugriff} und im Header {@code X-Archiv-Zugriff}: klein geschrieben. */
    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Genau einer der Codes {@code oeffentlich}, {@code mitglieder}, {@code vorstand}; sonst leer (kein Gross/Klein-Abgleich). */
    public static Optional<Zugriffsstufe> ausCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code().equals(code)).findFirst();
    }

    /** Die Codes aller Stufen bis und mit dieser, aufsteigend: Eingabe des Filters {@code zugriff = ANY(:stufen)}. */
    public List<String> bisUndMit() {
        return Arrays.stream(values()).filter(s -> s.ordinal() <= ordinal()).map(Zugriffsstufe::code).toList();
    }

    public boolean hoeherAls(Zugriffsstufe andere) {
        return compareTo(andere) > 0;
    }

    public static Zugriffsstufe kleinere(Zugriffsstufe a, Zugriffsstufe b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
