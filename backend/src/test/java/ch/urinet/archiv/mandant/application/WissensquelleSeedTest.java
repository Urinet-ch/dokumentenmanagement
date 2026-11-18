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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        DokumentartVorlage angepasst = wissensquelle("Mandanteneigener Name", List.of("titel", "datum", "partner"),
                null, false);
        var wissensquellenSeedLesen = new AtomicBoolean();

        List<DokumentartVorlage> ergebnis = MandantProvisionierung.mitWissensquelleWennFehlt(List.of(angepasst), () -> {
            wissensquellenSeedLesen.set(true);
            return seed.lesen();
        });

        assertThat(ergebnis).containsExactly(angepasst);
        assertThat(wissensquellenSeedLesen).isFalse();
    }

    @Test
    void wissensquellenAusDenSeedsHaltenDieFestenEigenschaften() {
        assertThat(SeedPruefung.wissensquelleFest(seed.lesen())).isTrue();
        var vhf = new KlassenpfadDokumentartSeed(json).lesen(new MandantCode("vhf-gsk"));
        assertThat(vhf).filteredOn(v -> v.code().equals("WISSENSQUELLE")).singleElement()
                .matches(SeedPruefung::wissensquelleFest);
        assertThat(SeedPruefung.wissensquelleFest(wissensquelle("Eigen", List.of("titel", "datum", "partner"),
                null, false))).isTrue();
    }

    @Test
    void mandantenSeedMitAbweichenderWissensquelleWirdAbgelehnt() {
        List<DokumentartVorlage> abweichend = List.of(
                wissensquelle("Mit KI", List.of("titel", "datum"), null, true),
                wissensquelle("Mit Frist", List.of("titel", "datum"), 10, false),
                wissensquelle("Ohne Datum", List.of("titel"), null, false));

        for (DokumentartVorlage art : abweichend) {
            assertThat(SeedPruefung.wissensquelleFest(art)).as(art.name()).isFalse();
            // die feste Prüfung läuft vor der Schema-Prüfung, darum ohne DokumentartPruefung
            assertThatThrownBy(() -> new SeedPruefung(null).pruefen(new MandantCode("mf-wq"), List.of(art)))
                    .isInstanceOfSatisfying(MandantFehler.class,
                            f -> assertThat(f.grund()).isEqualTo(MandantFehler.Grund.SEED_UNGUELTIG));
        }
    }

    private static DokumentartVorlage wissensquelle(String name, List<String> pflichtfelder, Integer jahre,
                                                    boolean kiVorschlag) {
        return new DokumentartVorlage("WISSENSQUELLE", name, null, List.of("frei"), pflichtfelder, null, "NORMAL",
                jahre, jahre == null ? null : "BELEGDATUM", jahre != null, true, false, List.of(), null, null,
                kiVorschlag, List.of(), null, 0, null);
    }
}
