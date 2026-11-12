package ch.urinet.archiv.verarbeitung.adapter.in.kennzahlen;

import ch.urinet.archiv.verarbeitung.application.Kennzahlen;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stellt die {@link Kennzahlen} als Micrometer-Gauges bereit (Req 16.4): {@code archiv_auftraege{mandant,status}},
 * {@code archiv_dokumente{mandant,status}} und {@code archiv_eingangskorb{mandant}}. Die Werte erhebt ein eigener Takt
 * ({@code archiv.kennzahlen.intervall}), nicht der Abruf von {@code /q/metrics}; ein neuer Mandant erhält seine Gauges
 * beim nächsten Takt. {@code /q/metrics} ist nur intern erreichbar (Traefik leitet nur {@code /q/health/live} weiter).
 */
@ApplicationScoped
public class MicrometerKennzahlen {

    private final Kennzahlen kennzahlen;
    private final MeterRegistry registry;
    private final Map<String, AtomicLong> werte = new ConcurrentHashMap<>();

    public MicrometerKennzahlen(Kennzahlen kennzahlen, MeterRegistry registry) {
        this.kennzahlen = kennzahlen;
        this.registry = registry;
    }

    @Scheduled(identity = "kennzahlen", every = "${archiv.kennzahlen.intervall}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void aktualisieren() {
        for (Kennzahlen.Stand stand : kennzahlen.erheben()) {
            String mandant = stand.mandant().wert();
            stand.auftraege().forEach((status, anzahl) -> wert("archiv.auftraege", mandant, status).set(anzahl));
            stand.dokumente().forEach((status, anzahl) -> wert("archiv.dokumente", mandant, status).set(anzahl));
            wert("archiv.eingangskorb", mandant, null).set(stand.eingangskorb());
        }
    }

    private AtomicLong wert(String name, String mandant, String status) {
        return werte.computeIfAbsent(name + "|" + mandant + "|" + status, k -> {
            AtomicLong wert = new AtomicLong();
            Gauge.Builder<AtomicLong> gauge = Gauge.builder(name, wert, AtomicLong::get).tag("mandant", mandant);
            if (status != null) {
                gauge.tag("status", status);
            }
            gauge.register(registry);
            return wert;
        });
    }
}
