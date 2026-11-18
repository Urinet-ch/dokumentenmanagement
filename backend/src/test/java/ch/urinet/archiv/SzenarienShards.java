package ch.urinet.archiv;

/**
 * Aufteilung der Backend-Szenarien: {@link SzenarienTest} läuft die Szenarien mit {@link #ABGRENZUNG},
 * {@link SzenarienRestTest} alle übrigen ausser dem isolierten Webseiten-Messfall. {@link #SHARD_3} läuft in
 * {@link ch.urinet.archiv.integration.WebseitenDateiMessfallTest}, damit dessen verlängertes Leerlauflimit die übrigen
 * Webseiten-Szenarien nicht beeinflusst. {@code SzenarienShardTest} beweist, dass jedes Szenario genau einmal läuft.
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

    private static final String STANDARD = AUSGENOMMEN + " and not @webseiten-datei-messfall";

    public static final String SHARD_1 = STANDARD + " and " + ABGRENZUNG;
    public static final String SHARD_2 = STANDARD + " and not (" + ABGRENZUNG + ")";

    public static final String SHARD_3 = "@webseiten-datei-messfall";

    private SzenarienShards() {
    }
}
