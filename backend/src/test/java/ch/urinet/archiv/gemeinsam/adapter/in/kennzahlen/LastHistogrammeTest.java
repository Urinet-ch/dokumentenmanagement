package ch.urinet.archiv.gemeinsam.adapter.in.kennzahlen;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LastHistogrammeTest {
    @Test
    void timerErfasstGrenzenOhneRundung() {
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new LastHistogramme().filter(true));
            var timer = registry.timer("http.server.requests", "uri", "/api/v1/dokumente");
            timer.record(1, TimeUnit.SECONDS);
            timer.record(2001, TimeUnit.MILLISECONDS);
            var buckets = timer.takeSnapshot().histogramCounts();
            assertThat(buckets).hasSize(2);
            assertThat(buckets[0].count()).isEqualTo(1);
            assertThat(buckets[1].count()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    @Test
    void nurAktivierteDokumentAnfragenErhaltenFesteGrenzen() {
        var config = DistributionStatisticConfig.builder().build();
        var id = new Meter.Id("http.server.requests", Tags.of("uri", "/api/v1/dokumente"),
                null, null, Meter.Type.TIMER);
        var filter = new LastHistogramme();
        assertThat(filter.filter(false).configure(id, config)).isSameAs(config);
        assertThat(filter.filter(true).configure(id, config).getServiceLevelObjectiveBoundaries())
                .containsExactly(1_000_000_000.0, 2_000_000_000.0);
        var anderer = new Meter.Id("http.server.requests", Tags.of("uri", "/api/v1/dokumente/{id}"),
                null, null, Meter.Type.TIMER);
        assertThat(filter.filter(true).configure(anderer, config)).isSameAs(config);
    }
}
