package ch.urinet.archiv.ki.adapter.out.kennzahlen;

import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

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
}
