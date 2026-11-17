package ch.urinet.archiv.gemeinsam;

import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.StreamSupport;

/**
 * Zeitgrenzen aus {@code application.properties} im Profil {@code prod}, ohne Quarkus-Start (Aufgabe 2.6, Review
 * PR #266, S1, K2): Dauern im Quarkus-Format ({@code 10m}, {@code PT10M}, {@code 600}).
 */
public final class ProdZeitgrenzen {

    private static final String AUFTRAG = "archiv.auftrag.zeitgrenze.";

    private final SmallRyeConfig konfiguration;

    private ProdZeitgrenzen(SmallRyeConfig konfiguration) {
        this.konfiguration = konfiguration;
    }

    public static ProdZeitgrenzen laden() throws IOException {
        return new ProdZeitgrenzen(new SmallRyeConfigBuilder().withProfile("prod").addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(
                        ProdZeitgrenzen.class.getClassLoader().getResource("application.properties")))
                .build());
    }

    public Duration dauer(String schluessel) {
        return dauerAus(konfiguration.getValue(schluessel, String.class));
    }

    public static Duration dauerAus(String wert) {
        return DurationConverter.parseDuration(wert.strip());
    }

    /** {@code archiv.auftrag.zeitgrenze.<typ>} je Auftragstyp. */
    public Map<String, Duration> auftraege() {
        Map<String, Duration> grenzen = new TreeMap<>();
        StreamSupport.stream(konfiguration.getPropertyNames().spliterator(), false)
                .filter(n -> n.startsWith(AUFTRAG))
                .forEach(n -> grenzen.put(n.substring(AUFTRAG.length()), dauer(n)));
        if (grenzen.isEmpty()) {
            throw new IllegalStateException("Keine " + AUFTRAG + "* in application.properties");
        }
        return grenzen;
    }

    /** Kürzeste Auftragszeitgrenze: Grenze für alles, was jeder Auftragstyp nutzen kann. */
    public Map.Entry<String, Duration> kuerzesteAuftragszeitgrenze() {
        return auftraege().entrySet().stream().min(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .orElseThrow();
    }
}
