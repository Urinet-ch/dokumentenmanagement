package ch.urinet.archiv.mandant.adapter.out.seed;

import ch.urinet.archiv.dokumentart.adapter.out.schema.NetworkntZusatzSchemaPruefer;
import ch.urinet.archiv.dokumentart.application.DokumentartPruefung;
import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.application.SeedPruefung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Jeder Seed unter {@code seed/*} (Produktion und Tests) geht durch die Seed-Prüfung der Provisionierung (Review PR #40);
 * nur der absichtlich ungültige Seed {@code probe-seed-ungueltig} bricht mit {@code SEED_UNGUELTIG} ab.
 */
class SeedPruefungTest {

    private static final String UNGUELTIG = "probe-seed-ungueltig";

    private final SeedPruefung pruefung =
            new SeedPruefung(new DokumentartPruefung(new NetworkntZusatzSchemaPruefer(Duration.ofMillis(50))));

    @Test
    void alleSeedsSindGueltig() throws IOException {
        List<Path> seeds = seeds();
        assertThat(seeds).extracting(s -> s.getParent().getFileName().toString())
                .contains("urinet", "vhf-gsk", "probe", UNGUELTIG);
        for (Path seed : seeds) {
            String mandant = seed.getParent().getFileName().toString();
            var vorlagen = KlassenpfadDokumentartSeed.vorlagen(new ObjectMapper().readTree(seed.toFile()), seed.toString());
            if (mandant.equals(UNGUELTIG)) {
                assertThatThrownBy(() -> pruefung.pruefen(new MandantCode(mandant), vorlagen)).as(mandant)
                        .isInstanceOfSatisfying(MandantFehler.class,
                                f -> assertThat(f.grund()).isEqualTo(MandantFehler.Grund.SEED_UNGUELTIG))
                        .hasMessageContaining("PROBE_ZYKLUS");
            } else {
                assertThatNoException().as(mandant).isThrownBy(() -> pruefung.pruefen(new MandantCode(mandant), vorlagen));
            }
        }
    }

    @Test
    void domainRegelnGeltenAuchImSeed() throws IOException {
        var vorlagen = KlassenpfadDokumentartSeed.vorlagen(new ObjectMapper().readTree("""
                {"dokumentarten": [{"code": "PROBE_GRUPPE", "name": "Probe", "gruppen": ["verband"],
                 "pflichtfelder": ["titel"], "vertraulichkeit": "NORMAL", "aufbewahrung_jahre": null,
                 "gesetzliche_pflicht": false}]}"""), "probe");
        assertThatThrownBy(() -> pruefung.pruefen(new MandantCode("probe"), vorlagen))
                .isInstanceOfSatisfying(MandantFehler.class,
                        f -> assertThat(f.grund()).isEqualTo(MandantFehler.Grund.SEED_UNGUELTIG))
                .hasMessageContaining("PROBE_GRUPPE");
    }

    private static List<Path> seeds() throws IOException {
        try (Stream<Path> main = Files.list(Path.of("src/main/resources/seed"));
             Stream<Path> test = Files.list(Path.of("src/test/resources/seed"))) {
            return Stream.concat(main, test).map(d -> d.resolve("dokumentarten.json")).filter(Files::exists).toList();
        }
    }
}
