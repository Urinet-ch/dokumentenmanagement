package ch.urinet.archiv.protokoll.adapter.in.job;

import ch.urinet.archiv.protokoll.application.ManifestPlanen;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestPlanerTest {

    @Test
    @Tag("req-16.24")
    void stufePPlantWederBeimStartNochImZeitplan() throws InterruptedException {
        CountDownLatch geplant = new CountDownLatch(1);
        ManifestPlanen planen = new ManifestPlanen(null, null, null) {
            @Override
            public void planen(Instant jetzt) {
                geplant.countDown();
            }

            @Override
            public void planenBeimStart(Instant jetzt) {
                geplant.countDown();
            }
        };
        ManifestPlaner planer = new ManifestPlaner(planen, "api,worker", true, "P");

        planer.start(null);
        planer.planen();

        assertThat(geplant.await(100, TimeUnit.MILLISECONDS)).isFalse();
    }
}
