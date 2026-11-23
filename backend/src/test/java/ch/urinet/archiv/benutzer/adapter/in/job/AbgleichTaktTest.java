package ch.urinet.archiv.benutzer.adapter.in.job;

import ch.urinet.archiv.benutzer.application.Abgleich;
import ch.urinet.archiv.gemeinsam.application.SchemasBereit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AbgleichTaktTest {

    @Test
    void startUndZeitplanLaufenImEigenenThreadNurMitWorker() throws Exception {
        List<String> threads = new CopyOnWriteArrayList<>();
        CountDownLatch begonnen = new CountDownLatch(1);
        CountDownLatch weiter = new CountDownLatch(1);
        Abgleich abgleich = new Abgleich(null, null, null, null, null, null, null, "urinet") {
            @Override
            public void ausfuehren() {
                threads.add(Thread.currentThread().getName());
                begonnen.countDown();
                try {
                    if (!weiter.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Starttest nicht freigegeben");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Starttest unterbrochen", e);
                }
            }
        };
        AbgleichTakt takt = new AbgleichTakt(abgleich, "api,worker", true, "B");

        takt.start(new SchemasBereit());
        assertThat(begonnen.await(10, TimeUnit.SECONDS)).isTrue();
        takt.geplant();
        weiter.countDown();
        new AbgleichTakt(abgleich(threads), "worker", true, "B").geplant();
        new AbgleichTakt(abgleich, "api", true, "B").start(new SchemasBereit());
        new AbgleichTakt(abgleich, "worker", true, "P").geplant();
        new AbgleichTakt(abgleich, "worker", false, "B").start(new SchemasBereit());

        assertThat(threads).containsExactly("verwaltungsabgleich", "verwaltungsabgleich");
    }

    private static Abgleich abgleich(List<String> threads) {
        return new Abgleich(null, null, null, null, null, null, null, "urinet") {
            @Override
            public void ausfuehren() {
                threads.add(Thread.currentThread().getName());
            }
        };
    }
}
