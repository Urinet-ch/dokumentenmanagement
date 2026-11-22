package ch.urinet.archiv.benutzer.adapter.in.job;

import ch.urinet.archiv.benutzer.application.Abgleich;
import ch.urinet.archiv.gemeinsam.application.SchemasBereit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AbgleichTaktTest {

    @Test
    void startUndZeitplanLaufenImEigenenThreadNurMitWorker() {
        List<String> threads = new ArrayList<>();
        Abgleich abgleich = abgleich(threads);
        AbgleichTakt takt = new AbgleichTakt(abgleich, "api,worker", true, "B");

        takt.start(new SchemasBereit());
        takt.geplant();
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
