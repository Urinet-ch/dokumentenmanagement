package ch.urinet.archiv.ki.adapter.out.kennzahlen;

import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Korrekturquote je Feld als Kennzahl (Req 7.7, 16.4): Zähler, Gauge, feste Felder, begrenzte Tags. */
class MicrometerKorrekturZaehlerTest {

    private static final MandantCode URINET = new MandantCode("urinet");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerKorrekturZaehler zaehler = new MicrometerKorrekturZaehler(registry);

    private double quote(String feld) {
        return registry.get("archiv.ki.feld.korrekturquote").tag("feld", feld).tag("mandant", "urinet").gauge().value();
    }

    @Test
    void quoteIstKorrigiertDurchBestaetigtJeFeld() {
        zaehler.zaehlen(URINET, Set.of(Feld.TITEL, Feld.ART), Set.of(Feld.TITEL));
        zaehler.zaehlen(URINET, Set.of(Feld.TITEL), Set.of());
        zaehler.zaehlen(URINET, Set.of(Feld.TITEL, Feld.ART), Set.of());
        assertThat(quote("titel")).isEqualTo(1.0 / 3);
        assertThat(quote("art")).isEqualTo(0.0);
    }

    @Test
    void ersterAufrufLegtAlleFelderMitNullAn() {
        zaehler.zaehlen(URINET, Set.of(Feld.TITEL), Set.of());
        for (Feld feld : Feld.values()) {
            assertThat(quote(feld.schluessel())).isZero();
            assertThat(registry.get("archiv.ki.feld.bestaetigt").tag("feld", feld.schluessel()).counter().count())
                    .isEqualTo(feld == Feld.TITEL ? 1.0 : 0.0);
        }
    }

    @Test
    void nurFeldUndMandantAlsTags() {
        zaehler.zaehlen(URINET, Set.of(Feld.TITEL), Set.of(Feld.TITEL));
        registry.getMeters().forEach(m -> assertThat(m.getId().getTags()).extracting(t -> t.getKey())
                .containsExactlyInAnyOrder("feld", "mandant"));
    }

    @Test
    void zweiterAufrufMeldetKeineReiheDoppelt() {
        zaehler.zaehlen(URINET, Set.of(Feld.TITEL), Set.of());
        int vorher = registry.getMeters().size();
        zaehler.zaehlen(URINET, Set.of(Feld.ART), Set.of());
        assertThat(registry.getMeters()).hasSize(vorher);
    }

    @Test
    void mandantenZaehlenGetrenntUndJederLegtSeineFelderAn() {
        MandantCode zweiter = new MandantCode("vhf-gsk");
        zaehler.zaehlen(URINET, Set.of(Feld.TITEL), Set.of(Feld.TITEL));
        zaehler.zaehlen(zweiter, Set.of(Feld.TITEL), Set.of());

        assertThat(quote("titel")).isEqualTo(1.0);
        assertThat(registry.get("archiv.ki.feld.korrekturquote").tag("feld", "titel").tag("mandant", "vhf-gsk")
                .gauge().value()).isZero();
        assertThat(registry.getMeters()).hasSize(2 * 3 * Feld.values().length);
    }

    @Test
    void keineAenderungOhneBestaetigtesFeldErzeugtNurDieNullReihen() {
        zaehler.zaehlen(URINET, Set.of(), Set.of());

        assertThat(registry.getMeters()).hasSize(3 * Feld.values().length);
        for (Feld feld : Feld.values()) {
            assertThat(quote(feld.schluessel())).isZero();
        }
    }

    @Test
    void gleichzeitigerErsterAufrufZaehltGenauUndLegtJedeReiheEinmalAn() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch los = new CountDownLatch(1);
        try {
            List<Future<?>> laeufe = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                laeufe.add(pool.submit(() -> {
                    los.await();
                    for (int n = 0; n < 50; n++) {
                        zaehler.zaehlen(URINET, Set.of(Feld.TITEL, Feld.ART), Set.of(Feld.TITEL));
                    }
                    return null;
                }));
            }
            los.countDown();
            for (Future<?> lauf : laeufe) {
                lauf.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(registry.get("archiv.ki.feld.bestaetigt").tag("feld", "titel").counter().count()).isEqualTo(800.0);
        assertThat(registry.get("archiv.ki.feld.korrigiert").tag("feld", "titel").counter().count()).isEqualTo(800.0);
        assertThat(quote("titel")).isEqualTo(1.0);
        assertThat(quote("art")).isZero();
        assertThat(registry.getMeters()).hasSize(3 * Feld.values().length);
    }
}
