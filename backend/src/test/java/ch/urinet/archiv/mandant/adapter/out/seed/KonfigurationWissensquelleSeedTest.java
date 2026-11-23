package ch.urinet.archiv.mandant.adapter.out.seed;

import ch.urinet.archiv.dokumentart.adapter.out.schema.NetworkntZusatzSchemaPruefer;
import ch.urinet.archiv.konfiguration.adapter.out.persistence.PanacheKonfigurationVersionsRepository;
import ch.urinet.archiv.konfiguration.adapter.out.schema.JsonKonfigurationsdateiCodec;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Art;
import ch.urinet.archiv.mandant.application.KonfigurationSeed;
import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.application.SeedPruefung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KonfigurationWissensquelleSeedTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KonfigurationSeed seed = new KlassenpfadKonfigurationSeed(new JsonKonfigurationsdateiCodec(json,
            new NetworkntZusatzSchemaPruefer(Duration.ofSeconds(2), Duration.ofSeconds(2)),
            new PanacheKonfigurationVersionsRepository(json)));

    @Test
    void urinetSeedEnthaeltKeineWissensquelleUndBehaeltSeineKonfiguration() {
        Konfiguration urinet = seed.lesen(new MandantCode("urinet"));
        assertThat(urinet.arten()).hasSize(11).extracting(Art::code)
                .contains("AUSGANGSRECHNUNG", "PROJEKTDOKUMENT").doesNotContain("WISSENSQUELLE");
        assertThat(urinet.einstellungen().geschaeftsjahrEnde()).isEqualTo("12-31");
        assertThat(urinet.regeln()).hasSize(3);
    }

    @Test
    void vhfSeedEnthaeltKeineWissensquelleUndBehaeltDieUebrigeArt() {
        Konfiguration vhf = seed.lesen(new MandantCode("vhf-gsk"));
        assertThat(vhf.arten()).extracting(Art::code)
                .containsExactly("PROBE_VHF_BULLETIN").doesNotContain("WISSENSQUELLE");
    }

    @Test
    void seedMitEntfallenerArtWirdAbgelehnt() {
        Konfiguration urinet = seed.lesen(new MandantCode("urinet"));
        var arten = new ArrayList<>(urinet.arten());
        Art vorlage = arten.getFirst();
        arten.add(new Art("WISSENSQUELLE", "Wissensquelle", null, vorlage.gruppen(),
                vorlage.pflichtfelder(), Konfiguration.OHNE_ZUSATZ, "NORMAL",
                new Konfiguration.Aufbewahrung(null, null), false, true));
        Konfiguration ungueltig = new Konfiguration(1, urinet.einstellungen(), arten);
        assertThatThrownBy(() -> new SeedPruefung(null).pruefen(new MandantCode("urinet"), ungueltig))
                .isInstanceOfSatisfying(MandantFehler.class,
                        fehler -> assertThat(fehler.grund()).isEqualTo(MandantFehler.Grund.SEED_UNGUELTIG));
    }
}
