package ch.urinet.archiv.mandant.adapter.out.seed;

import ch.urinet.archiv.dokumentart.adapter.out.schema.NetworkntZusatzSchemaPruefer;
import ch.urinet.archiv.dokumentart.application.DokumentartPruefung;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.adapter.out.persistence.PanacheKonfigurationVersionsRepository;
import ch.urinet.archiv.konfiguration.adapter.out.schema.JsonKonfigurationsdateiCodec;
import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.application.SeedPruefung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    // Fristen wie in application.properties: Die Seed-Prüfung misst CPU-Zeit mit eigener Seed-Frist.
    private final SeedPruefung pruefung = new SeedPruefung(new DokumentartPruefung(
            new NetworkntZusatzSchemaPruefer(Duration.ofMillis(50), Duration.ofSeconds(2))));
    private final JsonKonfigurationsdateiCodec codec = new JsonKonfigurationsdateiCodec(JSON,
            new NetworkntZusatzSchemaPruefer(Duration.ofMillis(50), Duration.ofSeconds(2)),
            new PanacheKonfigurationVersionsRepository(JSON));

    @Test
    void alleSeedsSindGueltig() throws IOException {
        List<Path> seeds = seeds();
        assertThat(seeds).extracting(s -> s.getParent().getFileName().toString())
                .contains("urinet", "vhf-gsk", "probe", UNGUELTIG);
        for (Path seed : seeds) {
            String mandant = seed.getParent().getFileName().toString();
            var konfiguration = lesen(seed);
            if (mandant.equals(UNGUELTIG)) {
                assertThatThrownBy(() -> pruefung.pruefen(new MandantCode(mandant), konfiguration)).as(mandant)
                        .isInstanceOfSatisfying(MandantFehler.class,
                                f -> assertThat(f.grund()).isEqualTo(MandantFehler.Grund.SEED_UNGUELTIG));
            } else {
                assertThatNoException().as(mandant)
                        .isThrownBy(() -> pruefung.pruefen(new MandantCode(mandant), konfiguration));
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
            return Stream.concat(main, test).map(d -> d.resolve("konfiguration.json")).filter(Files::exists).toList();
        }
    }

    private Konfiguration lesen(Path seed) throws IOException {
        if (seed.getParent().getFileName().toString().equals(UNGUELTIG)) {
            ObjectNode datei = (ObjectNode) JSON.readTree(seed.toFile());
            datei.remove("_zweck");
            datei.remove("format");
            datei.put("version", 1);
            datei.withArray("arten").forEach(art -> {
                if (art instanceof ObjectNode objekt && objekt.path("zusatzSchema").isObject()) {
                    try {
                        objekt.put("zusatzSchema", JSON.writeValueAsString(objekt.path("zusatzSchema")));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            });
            return JSON.treeToValue(datei, Konfiguration.class);
        }
        try {
            return codec.lesen(Files.readString(seed, StandardCharsets.UTF_8)).inhalt();
        } catch (FachFehler e) {
            throw new AssertionError("Ungültiges Konfigurationsschema in " + seed, e);
        }
    }
}
