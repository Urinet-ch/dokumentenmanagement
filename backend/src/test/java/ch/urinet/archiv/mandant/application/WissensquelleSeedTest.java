package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.adapter.out.seed.KlassenpfadDokumentartSeed;
import ch.urinet.archiv.mandant.adapter.out.seed.KlassenpfadWissensquelleSeed;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class WissensquelleSeedTest {

    private final ObjectMapper json = new ObjectMapper();
    private final WissensquelleSeed seed = new KlassenpfadWissensquelleSeed(json);

    @Test
    void seedEnthaeltDieVorgabenOhneMandantenspezifischeFelder() throws Exception {
        DokumentartVorlage art = seed.lesen();

        assertThat(art.code()).isEqualTo("WISSENSQUELLE");
        assertThat(art.name()).isEqualTo("Wissensquelle");
        assertThat(art.gruppen()).containsExactly("frei");
        assertThat(art.pflichtfelder()).containsExactly("titel", "datum");
        assertThat(art.vertraulichkeit()).isEqualTo("NORMAL");
        assertThat(art.aufbewahrungJahre()).isNull();
        assertThat(art.gesetzlichePflicht()).isFalse();
        assertThat(art.kiVorschlag()).isFalse();

        var schema = json.readTree(art.zusatzSchemaJson());
        assertThat(schema.path("properties").size()).isEqualTo(1);
        assertThat(schema.path("properties").has("herausgeber")).isTrue();
        assertThat(schema.path("properties").path("herausgeber").path("type").asText()).isEqualTo("string");
        assertThat(schema.has("zugriff")).isFalse();
        assertThat(schema.has("veroeffentlicht")).isFalse();
    }

    @Test
    void jederMandantSeedWirdNurUmEineFehlendeWissensquelleErgaenzt() {
        var urinet = new KlassenpfadDokumentartSeed(json).lesen(new MandantCode("urinet"));
        var wissensquellenSeedLesen = new AtomicBoolean();

        List<DokumentartVorlage> ergaenzt = MandantProvisionierung.mitWissensquelleWennFehlt(urinet, () -> {
            wissensquellenSeedLesen.set(true);
            return seed.lesen();
        });

        assertThat(ergaenzt).hasSize(urinet.size() + 1);
        assertThat(ergaenzt).extracting(DokumentartVorlage::code).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(urinet.stream().map(DokumentartVorlage::code),
                        java.util.stream.Stream.of("WISSENSQUELLE")).toList());
        assertThat(wissensquellenSeedLesen).isTrue();
    }

    @Test
    void vorhandeneWissensquelleAusMandantenSeedWirdNichtUeberschrieben() {
        DokumentartVorlage angepasst = new DokumentartVorlage("WISSENSQUELLE", "Mandanteneigener Name", null,
                List.of("frei"), List.of("titel", "datum"), null, "NORMAL", null, null, false, false);
        var wissensquellenSeedLesen = new AtomicBoolean();

        List<DokumentartVorlage> ergebnis = MandantProvisionierung.mitWissensquelleWennFehlt(List.of(angepasst), () -> {
            wissensquellenSeedLesen.set(true);
            return seed.lesen();
        });

        assertThat(ergebnis).containsExactly(angepasst);
        assertThat(wissensquellenSeedLesen).isFalse();
    }
}
