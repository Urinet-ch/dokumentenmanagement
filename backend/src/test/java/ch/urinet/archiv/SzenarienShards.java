package ch.urinet.archiv;

/**
 * Aufteilung der Backend-Szenarien auf zwei CI-Jobs ({@code .github/workflows/ci.yml}, Jobs «Backend-Szenarien»):
 * {@link SzenarienTest} läuft die Szenarien mit {@link #ABGRENZUNG}, {@link SzenarienRestTest} alle übrigen. Der zweite
 * Ausdruck ist das Komplement des ersten, nicht eine eigene Liste: Ein Szenario ohne oder mit neuem Spec-Tag landet im
 * Rest, nichts geht verloren. {@code SzenarienShardTest} beweist, dass jedes Szenario genau einmal läuft. Das Tag
 * trennt die Laufzeit ungefähr hälftig (Surefire-Berichte der CI); bei Schieflage hier anpassen.
 */
public final class SzenarienShards {

    /**
     * Was nie in den Backend-Szenarien läuft: Browser-Szenarien, Sicherung (eigener Lauf), Mandantenbericht und
     * Sichtbarkeit der Wissensbasis (je eigene Klasse).
     */
    public static final String AUSGENOMMEN =
            "not @browser and not @sicherung and not @mandant-bericht and not @wissen-sichtbarkeit";

    /** Tag-Ausdruck, der Shard 1 von Shard 2 trennt. */
    public static final String ABGRENZUNG = "@spec-01";

    public static final String SHARD_1 = AUSGENOMMEN + " and " + ABGRENZUNG;
    public static final String SHARD_2 = AUSGENOMMEN + " and not (" + ABGRENZUNG + ")";

    private SzenarienShards() {
    }
}
