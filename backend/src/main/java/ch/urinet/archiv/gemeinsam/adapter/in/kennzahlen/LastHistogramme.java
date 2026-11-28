package ch.urinet.archiv.gemeinsam.adapter.in.kennzahlen;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Opt-in: feste Serverzeit-Grenzen für die isolierte Messung, ohne zusätzliche Labels. */
@Singleton
public class LastHistogramme {
    @Produces
    @Singleton
    MeterFilter filter(@ConfigProperty(name = "archiv.last.histogramme", defaultValue = "false") boolean aktiv) {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!aktiv || !id.getName().equals("http.server.requests")
                        || !"/api/v1/dokumente".equals(id.getTag("uri"))) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(1_000_000_000.0, 2_000_000_000.0)
                        .build().merge(config);
            }
        };
    }
}
