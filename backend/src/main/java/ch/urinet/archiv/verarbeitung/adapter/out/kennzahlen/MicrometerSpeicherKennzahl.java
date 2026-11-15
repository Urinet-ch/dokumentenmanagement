package ch.urinet.archiv.verarbeitung.adapter.out.kennzahlen;

import ch.urinet.archiv.verarbeitung.application.SpeicherKennzahl;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Kennzahl {@code archiv_jvm_speicher_prozent{pool=…}} (Req 16.4, 16.5): Auslastung je Speicherbereich der JVM, ohne
 * Mandanten-Etiketten und ohne Inhalte.
 */
@ApplicationScoped
public class MicrometerSpeicherKennzahl implements SpeicherKennzahl {

    static final String NAME = "archiv.jvm.speicher.prozent";

    private final MeterRegistry registry;
    private final Map<String, AtomicReference<Double>> werte = new ConcurrentHashMap<>();

    public MicrometerSpeicherKennzahl(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void setzen(String pool, double prozent) {
        werte.computeIfAbsent(pool, p -> {
            AtomicReference<Double> wert = new AtomicReference<>(prozent);
            Gauge.builder(NAME, wert, AtomicReference::get).tag("pool", p)
                    .description("Auslastung eines Speicherbereichs der JVM in Prozent").register(registry);
            return wert;
        }).set(prozent);
    }
}
