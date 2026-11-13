package ch.urinet.archiv.verarbeitung.adapter.in.kennzahlen;

import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.DokumentUeberwachung;
import ch.urinet.archiv.verarbeitung.application.Kennzahlen;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Gauges eines Mandanten ohne Stand verschwinden, statt den letzten Wert zu behalten (Req 16.4, Review PR #91). */
class MicrometerKennzahlenTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode VHF = new MandantCode("vhf-gsk");

    private final List<MandantCode> aktiv = new ArrayList<>(List.of(URINET, VHF));
    private final Set<MandantCode> scheitert = new HashSet<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerKennzahlen kennzahlen = new MicrometerKennzahlen(new Kennzahlen(register(),
            mandant -> Map.of("FEHLER", 2L), dokumente()), registry);

    @Test
    void gescheiterteErhebungEntferntDieGaugesDesMandanten() {
        kennzahlen.aktualisieren();
        assertThat(fehler("vhf-gsk")).isEqualTo(3.0);

        scheitert.add(VHF);
        kennzahlen.aktualisieren();

        assertThat(registry.find("archiv.dokumente").tag("mandant", "vhf-gsk").gauges()).isEmpty();
        assertThat(registry.find("archiv.eingangskorb").tag("mandant", "vhf-gsk").gauges()).isEmpty();
        assertThat(fehler("urinet")).isEqualTo(3.0);

        scheitert.clear();
        kennzahlen.aktualisieren();
        assertThat(fehler("vhf-gsk")).as("nach erfolgreicher Erhebung wieder da").isEqualTo(3.0);
    }

    @Test
    void nichtMehrAktiverMandantVerliertSeineGauges() {
        kennzahlen.aktualisieren();

        aktiv.remove(VHF);
        kennzahlen.aktualisieren();

        assertThat(registry.find("archiv.auftraege").tag("mandant", "vhf-gsk").gauges()).isEmpty();
        assertThat(registry.find("archiv.auftraege").tag("mandant", "urinet").gauges()).isNotEmpty();
    }

    private double fehler(String mandant) {
        return registry.get("archiv.dokumente").tag("mandant", mandant).tag("status", "FEHLER").gauge().value();
    }

    private DokumentUeberwachung dokumente() {
        return new DokumentUeberwachung() {
            @Override
            public List<Befund> laengerAls(MandantCode mandant, Duration grenze) {
                return List.of();
            }

            @Override
            public Map<String, Long> anzahlJeStatus(MandantCode mandant) {
                if (scheitert.contains(mandant)) {
                    throw new IllegalStateException("Schema gesperrt");
                }
                return Map.of("FEHLER", 3L);
            }
        };
    }

    private MandantRegister register() {
        return new MandantRegister() {
            @Override
            public boolean existiert(MandantCode code) {
                return aktiv.contains(code);
            }

            @Override
            public void eintragen(Mandant mandant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Mandant> aktive() {
                return aktiv.stream().map(c -> new Mandant(c, c.wert(), c.schema(), "b-" + c.wert(),
                        Geschaeftsjahresende.STANDARD, true)).toList();
            }

            @Override
            public Optional<Instant> erstellt(MandantCode code) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
